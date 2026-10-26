package watch.nabu.application.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.storage.FileStoragePort;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * GDPR Article 17 — right to erasure.
 *
 * <p>Implements a two-phase deletion: soft-delete (immediate, reversible within
 * the grace period) and hard-delete (irreversible, runs after 30 days).
 *
 * <p>No JPA cascade deletes are used. All cleanup is performed manually in
 * strict FK-dependency order to avoid constraint violations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private static final int GRACE_PERIOD_DAYS = 30;

    private final UserRepository userRepository;
    private final OrganizationMemberRepository orgMemberRepository;
    private final OrganizationRepository orgRepository;
    private final WebsiteRepository websiteRepository;
    private final MonitorRepository monitorRepository;
    private final ScanLogRepository scanLogRepository;
    private final StoredFileRepository storedFileRepository;
    private final MonitorStatusOverrideRepository monitorStatusOverrideRepository;
    private final WebsiteCompliancePathRepository websiteCompliancePathRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookConfigRepository webhookConfigRepository;
    private final OrgNotificationSettingRepository orgNotificationSettingRepository;
    private final NotificationRepository notificationRepository;
    private final InternalMessageRepository internalMessageRepository;
    private final FileStoragePort fileStoragePort;

    /**
     * Soft-delete: marks the account for deletion after the grace period.
     */
    @Transactional
    public void requestDeletion(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        user.setStatus(User.AccountStatus.DELETED);
        user.setDeletionRequestedAt(Instant.now());
        userRepository.save(user);
        log.info("[gdpr] Deletion requested for userId={}", userId);
    }

    /**
     * Finds all users past the grace period and hard-deletes each in its own
     * transaction. Returns the number of accounts purged.
     */
    public int purgeExpiredAccounts() {
        Instant cutoff = Instant.now().minus(GRACE_PERIOD_DAYS, ChronoUnit.DAYS);
        List<User> expired = userRepository.findByStatusAndDeletionRequestedAtBefore(
                User.AccountStatus.DELETED, cutoff);

        int purged = 0;
        for (User user : expired) {
            try {
                hardDeleteUser(user);
                purged++;
            } catch (Exception e) {
                log.error("[gdpr] Failed to purge userId={}: {}", user.getId(), e.getMessage(), e);
            }
        }
        return purged;
    }

    /**
     * Irreversibly removes all data associated with the user.
     *
     * <p>Sole-owner organizations are fully cascade-deleted. Shared organizations
     * only have the user's audit FK references nullified and the membership removed.
     */
    @Transactional
    public void hardDeleteUser(User user) {
        UUID userId = user.getId();
        String email = user.getEmail();
        log.info("[gdpr] Hard-deleting userId={} email={}", userId, email);

        // ── 1. Determine sole-owner vs shared orgs ──────────────────────────
        List<OrganizationMember> memberships = orgMemberRepository.findAllByUserId(userId);

        Set<UUID> soleOwnerOrgIds = new HashSet<>();
        Set<UUID> sharedOrgIds = new HashSet<>();

        for (OrganizationMember membership : memberships) {
            UUID orgId = membership.getOrganization().getId();
            if (membership.getRole() == OrganizationMember.Role.OWNER
                    && orgMemberRepository.countByOrganizationId(orgId) == 1) {
                soleOwnerOrgIds.add(orgId);
            } else {
                sharedOrgIds.add(orgId);
            }
        }

        // ── 2. Full cascade delete for sole-owner orgs ──────────────────────
        if (!soleOwnerOrgIds.isEmpty()) {
            cascadeDeleteOrganizations(soleOwnerOrgIds);
        }

        // ── 3. Nullify audit FKs in shared orgs ────────────────────────────
        if (!sharedOrgIds.isEmpty()) {
            scanLogRepository.nullifyTriggeredBy(userId);
            scanLogRepository.nullifyReportedBy(userId);
            monitorRepository.nullifyCreatedBy(userId);
            websiteRepository.nullifyAddedBy(userId);
        }

        // ── 4. Delete user-level data ──────────────────────────────────────
        orgNotificationSettingRepository.deleteByUserId(userId);
        orgMemberRepository.deleteByUserId(userId);
        notificationRepository.deleteByUserId(userId);
        internalMessageRepository.deleteBySenderOrReceiver(email);

        // ── 5. Delete user record ──────────────────────────────────────────
        userRepository.delete(user);
        log.info("[gdpr] Hard-delete complete for userId={}", userId);
    }

    /**
     * Deletes all data owned by the given organizations in strict FK order.
     */
    private void cascadeDeleteOrganizations(Set<UUID> orgIds) {
        Collection<UUID> orgIdList = List.copyOf(orgIds);

        List<UUID> websiteIds = websiteRepository.findAllByOrganizationIdIn(orgIdList).stream()
                .map(Website::getId).toList();

        List<UUID> monitorIds = websiteIds.isEmpty()
                ? List.of()
                : monitorRepository.findByWebsiteIdIn(websiteIds).stream()
                  .map(Monitor::getId).toList();

        List<UUID> webhookConfigIds = webhookConfigRepository.findByOrganizationIdIn(orgIdList).stream()
                .map(WebhookConfig::getId).toList();

        // ── Cascade delete in FK order ──────────────────────────────────────

        // 1. StoredFile (MinIO cleanup + DB)
        cleanupStoredFiles(orgIdList);

        // 2. ScanLog (FK → monitors)
        if (!monitorIds.isEmpty()) {
            scanLogRepository.deleteByMonitorIdIn(monitorIds);
        }

        // 3. MonitorStatusOverride (FK → monitors)
        if (!monitorIds.isEmpty()) {
            monitorStatusOverrideRepository.deleteByMonitorIdIn(monitorIds);
        }

        // 4. Monitor (FK → websites)
        if (!websiteIds.isEmpty()) {
            monitorRepository.deleteByWebsiteIdIn(websiteIds);
        }

        // 5. WebsiteCompliancePath (FK → websites)
        if (!websiteIds.isEmpty()) {
            websiteCompliancePathRepository.deleteByWebsiteIdIn(websiteIds);
        }

        // 6. Website (FK → organizations)
        websiteRepository.deleteByOrganizationIdIn(orgIdList);

        // 7. WebhookDelivery (FK → webhook_configs)
        if (!webhookConfigIds.isEmpty()) {
            webhookDeliveryRepository.deleteByWebhookConfigIdIn(webhookConfigIds);
        }

        // 8. WebhookConfig (FK → organizations)
        webhookConfigRepository.deleteByOrganizationIdIn(orgIdList);

        // 9–10. OrgNotificationSetting + OrganizationMember
        // (handled in hardDeleteUser after this call)

        // 11. Organization
        orgRepository.deleteAllByIdIn(orgIdList);

        log.info("[gdpr] Cascade-deleted {} organizations with {} websites, {} monitors",
                orgIds.size(), websiteIds.size(), monitorIds.size());
    }

    /**
     * Removes stored files from MinIO and the database. Logs failures but does
     * not abort the deletion.
     */
    private void cleanupStoredFiles(Collection<UUID> orgIds) {
        List<StoredFile> files = storedFileRepository.findByOrganizationIdIn(orgIds);

        for (StoredFile file : files) {
            try {
                fileStoragePort.delete(file.getBucketName(), file.getStoragePath());
            } catch (Exception e) {
                log.warn("[gdpr] Failed to delete file from storage: bucket={} path={}: {}",
                        file.getBucketName(), file.getStoragePath(), e.getMessage());
            }
        }

        if (!orgIds.isEmpty()) {
            storedFileRepository.deleteByOrganizationIdIn(orgIds);
        }
    }
}
