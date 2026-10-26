package watch.nabu.application.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.MonitorNotFoundException;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.OrganizationMember.Role;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.MonitorStatusOverrideRepository;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages manual severity overrides for monitors.
 *
 * <p>Only users with {@code OWNER} role in the monitor's organization can
 * create or remove overrides.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorOverrideService {

    private final MonitorRepository monitorRepository;
    private final ScanLogRepository scanLogRepository;
    private final MonitorStatusOverrideRepository overrideRepository;
    private final OrganizationMemberRepository memberRepository;

    /**
     * Creates or updates (upsert) a severity override for the given monitor.
     *
     * @param monitorId        the monitor to override
     * @param scanLogId        the scan result that was reviewed
     * @param overrideSeverity the desired effective severity
     * @param note             optional reason for the override
     * @param user             the authenticated user performing the action
     * @return the persisted override
     * @throws MonitorNotFoundException                                  if the monitor does not exist
     * @throws org.springframework.security.access.AccessDeniedException if user is not OWNER in the org
     */
    @Transactional
    public MonitorStatusOverride createOrUpdate(
            UUID monitorId, UUID scanLogId, Severity overrideSeverity, String note, User user) {

        Monitor monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));

        UUID orgId = monitor.getWebsite().getOrganization().getId();
        assertOwnerRole(orgId, user.getId());

        ScanLog scanLog = scanLogRepository.findById(scanLogId)
                .orElseThrow(() -> new IllegalArgumentException("ScanLog not found: " + scanLogId));

        if (!scanLog.getMonitor().getId().equals(monitorId)) {
            throw new IllegalArgumentException("ScanLog does not belong to this monitor");
        }

        MonitorStatusOverride override = overrideRepository.findByMonitorId(monitorId)
                .orElseGet(() -> MonitorStatusOverride.builder()
                        .monitor(monitor)
                        .build());

        override.setBaselineSeverity(scanLog.getSeverity());
        override.setOverrideSeverity(overrideSeverity);
        override.setScanLog(scanLog);
        override.setOverriddenBy(user);
        override.setOverriddenAt(Instant.now());
        override.setNote(note);

        MonitorStatusOverride saved = overrideRepository.save(override);

        log.info("[override] upsert: monitorId={} baseline={} override={} by={}",
                monitorId, saved.getBaselineSeverity(), overrideSeverity, user.getEmail());

        return saved;
    }

    /**
     * Removes the override for the given monitor.
     *
     * @throws MonitorNotFoundException                                  if the monitor does not exist
     * @throws org.springframework.security.access.AccessDeniedException if user is not OWNER in the org
     */
    @Transactional
    public void remove(UUID monitorId, User user) {
        Monitor monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));

        UUID orgId = monitor.getWebsite().getOrganization().getId();
        assertOwnerRole(orgId, user.getId());

        overrideRepository.deleteByMonitorId(monitorId);

        log.info("[override] removed: monitorId={} by={}", monitorId, user.getEmail());
    }

    /**
     * Returns the active override for the given monitor, if any.
     * This is a read-only operation — all authenticated users can see it.
     */
    @Transactional(readOnly = true)
    public Optional<MonitorStatusOverride> getOverride(UUID monitorId) {
        return overrideRepository.findByMonitorId(monitorId);
    }

    // ── guards ──────────────────────────────────────────────────────────────

    private void assertOwnerRole(UUID orgId, UUID userId) {
        boolean isOwner = memberRepository.existsByOrganizationIdAndUserIdAndRole(
                orgId, userId, Role.OWNER);
        if (!isOwner) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only organization owners can manage status overrides");
        }
    }
}
