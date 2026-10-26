package watch.nabu.application.abuse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.application.notification.SiteLifecycleEmailService;
import watch.nabu.application.verification.UserVerificationService;
import watch.nabu.domain.model.AbuseReport;
import watch.nabu.domain.model.Notification;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.AbuseReportRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin triage for {@link AbuseReport} rows. Only users holding {@code
 * ROLE_ADMIN} can reach the controller — the service assumes that gate has
 * been enforced upstream.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   PENDING ─ uphold ──▶ UPHELD ─ ownership-proven ─▶ OWNERSHIP_PROVEN
 *        │                  │
 *        │                  └─ deadline expires ──▶ SITE_REMOVED (hard-delete)
 *        │
 *        └─ dismiss ──▶ DISMISSED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AbuseAdminService {

    private final AbuseReportRepository reportRepository;
    private final WebsiteRepository websiteRepository;
    private final UserVerificationService userVerificationService;
    private final NotificationService notificationService;
    private final SiteLifecycleEmailService siteLifecycleEmailService;

    @Transactional(readOnly = true)
    public Page<AbuseReport> list(AbuseReport.Status status, Pageable pageable) {
        return reportRepository.findAdminPage(status, pageable);
    }

    public AbuseReport uphold(UUID reportId) {
        AbuseReport report = requirePending(reportId);
        Website site = report.getWebsite();
        if (site.getStatus() == Website.WebsiteStatus.ACTIVE) {
            site.setStatus(Website.WebsiteStatus.PAUSED);
            websiteRepository.save(site);
        }
        report.setStatus(AbuseReport.Status.UPHELD);
        log.warn("[abuse] UPHELD: reportId={} websiteId={} url={} pausedAt={}",
                reportId, site.getId(), site.getUrl(), Instant.now());
        notifyOwner(site, Notification.NotificationType.ABUSE_REPORT,
                "Website paused after abuse report",
                "An abuse report was upheld against " + site.getUrl()
                        + ". Scans are paused. Complete DNS TXT verification or contact support within "
                        + "48 hours to reactivate, otherwise the site will be removed.");
        siteLifecycleEmailService.sendAbuseUpheld(site.getAddedBy(), site);
        return report;
    }

    public AbuseReport dismiss(UUID reportId) {
        AbuseReport report = requirePending(reportId);
        report.setStatus(AbuseReport.Status.DISMISSED);
        report.setResolvedAt(Instant.now());
        log.info("[abuse] DISMISSED: reportId={}", reportId);
        return report;
    }

    public AbuseReport markOwnershipProven(UUID reportId) {
        AbuseReport report = requireStatus(reportId, AbuseReport.Status.UPHELD);
        Website site = report.getWebsite();
        if (site.getStatus() == Website.WebsiteStatus.PAUSED) {
            site.setStatus(Website.WebsiteStatus.ACTIVE);
            websiteRepository.save(site);
        }
        report.setStatus(AbuseReport.Status.OWNERSHIP_PROVEN);
        report.setResolvedAt(Instant.now());
        log.info("[abuse] OWNERSHIP_PROVEN: reportId={} websiteId={}",
                reportId, site.getId());
        notifyOwner(site, Notification.NotificationType.ABUSE_REPORT,
                "Website reactivated",
                "Ownership of " + site.getUrl() + " was accepted. Scans have resumed.");
        siteLifecycleEmailService.sendAbuseOwnershipRestored(site.getAddedBy(), site);
        return report;
    }

    /**
     * Scheduler entry point: UPHELD reports whose proof_deadline_at has passed
     * trigger a hard-delete of the underlying website. Cascading FKs prune
     * monitors, scan_logs, compliance paths, verifications, and the abuse_report
     * rows themselves (SITE_REMOVED becomes equivalent to "row deleted" — the
     * report is its own tombstone via the audit logs).
     *
     * <p>Owner notification + email are sent before the delete so {@code site}
     * fields remain readable. Orphan reports (no website attached) are stamped
     * SITE_REMOVED in place because there is nothing to delete.
     */
    public int sweepExpired() {
        List<AbuseReport> expired = reportRepository
                .findAllByStatusAndProofDeadlineAtBefore(AbuseReport.Status.UPHELD, Instant.now());
        if (expired.isEmpty()) return 0;

        // Group reports by site so we delete each site exactly once even when
        // it has multiple UPHELD reports past their deadline.
        java.util.Map<UUID, AbuseReport> reportBySite = new java.util.LinkedHashMap<>();
        Instant now = Instant.now();
        int orphanCount = 0;
        for (AbuseReport report : expired) {
            Website site = report.getWebsite();
            if (site == null) {
                report.setStatus(AbuseReport.Status.SITE_REMOVED);
                report.setResolvedAt(now);
                orphanCount++;
                continue;
            }
            reportBySite.putIfAbsent(site.getId(), report);
        }

        for (AbuseReport report : reportBySite.values()) {
            Website site = report.getWebsite();
            User owner = site.getAddedBy();
            String url = site.getUrl();
            UUID siteId = site.getId();

            notifyOwner(site, Notification.NotificationType.ABUSE_REPORT,
                    "Website removed",
                    url + " was removed after an upheld abuse report "
                            + "and no ownership proof within 48 hours. Contact support to appeal.");
            siteLifecycleEmailService.sendAbuseSiteRemoved(owner, site);

            websiteRepository.delete(site);
            userVerificationService.requestRecompute(owner);
            log.warn("[abuse] SITE_REMOVED (deadline expired): websiteId={} url={}", siteId, url);
        }

        return reportBySite.size() + orphanCount;
    }

    private void notifyOwner(Website site, Notification.NotificationType type,
                             String title, String message) {
        User owner = site.getAddedBy();
        if (owner == null) return;
        notificationService.sendSiteLifecycleNotification(
                owner.getId(), site.getId(), site.getUrl(),
                site.getOrganization() != null ? site.getOrganization().getId() : null,
                type, title, message);
    }

    private AbuseReport requirePending(UUID id) {
        return requireStatus(id, AbuseReport.Status.PENDING);
    }

    private AbuseReport requireStatus(UUID id, AbuseReport.Status expected) {
        AbuseReport report = reportRepository.findByIdWithWebsite(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        if (report.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Report is in status " + report.getStatus() + ", expected " + expected);
        }
        return report;
    }
}
