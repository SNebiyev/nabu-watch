package watch.nabu.application.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.OrgNotificationSetting;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrgNotificationSettingRepository;

import java.util.*;

/**
 * Manages owner-level notification settings for organization members.
 *
 * <p>Two-level model: an email is sent only when <strong>both</strong>
 * the owner-level flag (this service) AND the user-level flag
 * ({@link User#isEmailAlertsEnabled()}) are {@code true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgNotificationSettingsService {

    private final OrgNotificationSettingRepository settingRepository;

    /**
     * Initializes notification settings for a new member.
     * OWNER role gets both flags true; MEMBER gets both false.
     */
    @Transactional
    public void initDefaults(Organization organization, User user, OrganizationMember.Role role) {
        boolean enabled = role == OrganizationMember.Role.OWNER;
        settingRepository.save(OrgNotificationSetting.builder()
                .organization(organization)
                .user(user)
                .alertsEnabled(enabled)
                .digestEnabled(enabled)
                .build());
        log.debug("[org-notif] Initialized defaults for orgId={} userId={} role={}",
                organization.getId(), user.getId(), role);
    }

    /**
     * Returns the setting for a specific org+user pair.
     */
    @Transactional(readOnly = true)
    public OrgNotificationSetting getForMember(UUID orgId, UUID userId) {
        return settingRepository.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Notification setting not found: orgId=" + orgId + " userId=" + userId));
    }

    /**
     * Returns all settings for an organization (for owner to view the full list).
     */
    @Transactional(readOnly = true)
    public List<OrgNotificationSetting> listForOrganization(UUID orgId) {
        return settingRepository.findAllByOrganizationIdWithUser(orgId);
    }

    /**
     * Owner updates a member's notification settings.
     */
    @Transactional
    public OrgNotificationSetting updateByOwner(UUID orgId, UUID userId,
                                                Boolean alertsEnabled, Boolean digestEnabled) {
        OrgNotificationSetting setting = settingRepository
                .findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Notification setting not found: orgId=" + orgId + " userId=" + userId));

        if (alertsEnabled != null) {
            setting.setAlertsEnabled(alertsEnabled);
        }
        if (digestEnabled != null) {
            setting.setDigestEnabled(digestEnabled);
        }
        return settingRepository.save(setting);
    }

    /**
     * Returns the set of user IDs that have org-level alerts enabled for the
     * given organization. Used by {@code ScanAlertEmailService} to intersect
     * with user-level preferences.
     */
    @Transactional(readOnly = true)
    public Set<UUID> getAlertEnabledUserIds(UUID orgId) {
        return new HashSet<>(settingRepository.findAlertEnabledUserIdsByOrganizationId(orgId));
    }

    /**
     * Returns the set of org IDs where the user has org-level digest enabled.
     * Used by {@code WeeklyDigestService} to intersect with user-level preferences.
     */
    @Transactional(readOnly = true)
    public Set<UUID> getDigestEnabledOrgIds(UUID userId) {
        return new HashSet<>(settingRepository.findDigestEnabledOrgIdsByUserId(userId));
    }

    /**
     * Removes the notification setting when a member is removed from an organization.
     */
    @Transactional
    public void deleteForMember(UUID orgId, UUID userId) {
        settingRepository.deleteByOrganizationIdAndUserId(orgId, userId);
    }
}
