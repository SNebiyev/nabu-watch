package watch.nabu.application.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds and sends the weekly digest email every Monday at 08:00 UTC.
 *
 * <h3>Contents (per user)</h3>
 * <ul>
 *   <li>Per-organization: count of scans run in the last 7 days.</li>
 *   <li>Per-organization: count of currently-open HIGH/CRITICAL issues.</li>
 *   <li>Up to 10 currently-failing websites across the user's orgs.</li>
 * </ul>
 *
 * <h3>Opt-in</h3>
 * <p>Only users with {@code weeklyDigestEnabled = true} receive the digest.
 * Users with no failing websites and no scans in the last week are skipped
 * entirely so the inbox stays quiet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyDigestService {

    private static final int FAILING_WEBSITES_LIMIT = 10;
    private static final int DIGEST_WINDOW_DAYS = 7;

    private final UserRepository userRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final ScanLogRepository scanLogRepository;
    private final OrgNotificationSettingsService orgNotificationSettingsService;
    private final MessageDispatcher messageDispatcher;
    private final UnsubscribeTokenService unsubscribeTokenService;

    @Value("${nabu.mail.base-url:http://localhost:3000}")
    private String baseUrl;

    /**
     * Cron: every Monday at 08:00 UTC.
     * Spring {@code @Scheduled} expression: {@code seconds minutes hour day-of-month month day-of-week}.
     */
    @Scheduled(cron = "0 0 8 ? * MON", zone = "UTC")
    public void sendWeeklyDigests() {
        log.info("[weekly-digest] tick started");
        int sent = 0;
        int skipped = 0;
        Instant since = Instant.now().minus(DIGEST_WINDOW_DAYS, ChronoUnit.DAYS);
        Map<UUID, OrgMetrics> orgCache = new HashMap<>();

        List<User> candidates = userRepository
                .findAllByWeeklyDigestEnabledTrueAndStatus(User.AccountStatus.ACTIVE);
        for (User user : candidates) {
            try {
                if (buildAndSendFor(user, since, orgCache)) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("[weekly-digest] failed for userId={}: {}", user.getId(), e.getMessage());
            }
        }
        log.info("[weekly-digest] tick complete: sent={} skipped={}", sent, skipped);
    }

    /**
     * Builds the digest payload for a single user and dispatches it.
     * Returns {@code true} when an email was sent, {@code false} when the user
     * had no relevant activity (and therefore nothing to send).
     *
     * <p>Package-private for unit tests.
     */
    @Transactional(readOnly = true)
    public boolean buildAndSendFor(User user, Instant since, Map<UUID, OrgMetrics> orgCache) {
        List<OrganizationMember> memberships = organizationMemberRepository
                .findAllByUserIdWithOrganization(user.getId());
        if (memberships.isEmpty()) {
            return false;
        }

        // Owner-level flags: only include orgs where digestEnabled=true
        Set<UUID> digestEnabledOrgIds = orgNotificationSettingsService
                .getDigestEnabledOrgIds(user.getId());

        List<OrgDigest> perOrg = new ArrayList<>();
        List<FailingWebsite> failingWebsites = new ArrayList<>();
        long totalScans = 0;
        long totalOpenIssues = 0;

        for (OrganizationMember m : memberships) {
            UUID orgId = m.getOrganization().getId();
            // AND logic: skip orgs where owner-level digest is disabled
            if (!digestEnabledOrgIds.contains(orgId)) {
                continue;
            }
            String orgName = m.getOrganization().getName();
            OrgMetrics metrics = orgCache.computeIfAbsent(orgId, id -> loadMetrics(id, since));
            totalScans += metrics.scans;
            totalOpenIssues += metrics.openIssues;
            perOrg.add(new OrgDigest(orgName, metrics.scans, metrics.openIssues));

            if (failingWebsites.size() < FAILING_WEBSITES_LIMIT) {
                int remaining = FAILING_WEBSITES_LIMIT - failingWebsites.size();
                for (Object[] row : metrics.failingWebsites) {
                    if (failingWebsites.size() >= FAILING_WEBSITES_LIMIT) break;
                    String url = (String) row[1];
                    Number count = (Number) row[2];
                    failingWebsites.add(new FailingWebsite(url, count.longValue()));
                    if (--remaining == 0) break;
                }
            }
        }

        if (totalScans == 0 && totalOpenIssues == 0 && failingWebsites.isEmpty()) {
            log.debug("[weekly-digest] no activity for userId={}, skipping", user.getId());
            return false;
        }

        String subject = "[Nabu Watch] Your weekly compliance digest";
        String unsubscribeUrl = unsubscribeTokenService.buildUnsubscribeUrl(user.getId(), "digest");
        String displayName = user.getFullName() != null ? user.getFullName() : user.getEmail();
        String html = renderHtml(displayName, perOrg, failingWebsites, totalScans, totalOpenIssues, unsubscribeUrl);
        String text = renderText(displayName, perOrg, failingWebsites, totalScans, totalOpenIssues, unsubscribeUrl);

        messageDispatcher.dispatch(Message.email(user.getEmail(), subject, html, text));
        return true;
    }

    private String renderHtml(String name, List<OrgDigest> perOrg,
                              List<FailingWebsite> failing, long totalScans, long totalOpen,
                              String unsubscribeUrl) {
        StringBuilder orgRows = new StringBuilder();
        for (OrgDigest o : perOrg) {
            orgRows.append("""
                    <tr>
                      <td style="padding:6px 8px;color:#e2e8f0;font-size:13px;">%s</td>
                      <td style="padding:6px 8px;color:#94a3b8;font-size:13px;text-align:right;">%d scans</td>
                      <td style="padding:6px 8px;color:%s;font-size:13px;text-align:right;">%d open</td>
                    </tr>
                    """.formatted(HtmlEscape.escape(o.orgName()), o.scans(),
                    o.openIssues() > 0 ? "#fca5a5" : "#94a3b8", o.openIssues()));
        }

        StringBuilder failRows = new StringBuilder();
        if (failing.isEmpty()) {
            failRows.append("<li style=\"color:#94a3b8;font-size:13px;\">No currently-failing websites. Nice work.</li>");
        } else {
            for (FailingWebsite f : failing) {
                failRows.append("<li style=\"color:#e2e8f0;font-size:13px;margin:4px 0;\">")
                        .append(HtmlEscape.escape(f.url()))
                        .append(" <span style=\"color:#fca5a5;\">· ")
                        .append(f.failingMonitors())
                        .append(" failing monitor(s)</span></li>");
            }
        }

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background-color:#0f172a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                <div style="max-width:600px;margin:40px auto;background-color:#1e293b;border-radius:16px;border:1px solid #334155;padding:40px;">
                  <h1 style="color:#f8fafc;font-size:20px;margin:0 0 4px;">Nabu Watch</h1>
                  <p style="color:#64748b;font-size:12px;margin:0 0 24px;">Your weekly digest</p>
                  <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 16px;">Hi %s,</p>
                  <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 24px;">
                    Here is what happened across your organizations in the last 7 days:
                    <strong style="color:#e2e8f0;">%d scans run</strong>,
                    <strong style="color:%s;">%d open high/critical issues</strong>.
                  </p>
                  <h2 style="color:#f1f5f9;font-size:15px;margin:24px 0 8px;">Per organization</h2>
                  <table style="width:100%%;border-collapse:collapse;background-color:#0f172a;border-radius:8px;overflow:hidden;">%s</table>
                  <h2 style="color:#f1f5f9;font-size:15px;margin:24px 0 8px;">Currently failing websites (top %d)</h2>
                  <ul style="padding-left:20px;margin:0 0 24px;">%s</ul>
                  <div style="text-align:center;margin:32px 0;">
                    <a style="display:inline-block;padding:12px 32px;background-color:#0ea5e9;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;border-radius:8px;" href="%s/dashboard">
                      Open dashboard
                    </a>
                  </div>
                  <hr style="border:none;border-top:1px solid #334155;margin:24px 0 12px;"/>
                  <p style="color:#475569;font-size:11px;text-align:center;margin:0;">
                    You can disable the weekly digest at any time in your
                    <a style="color:#475569;text-decoration:underline;" href="%s/dashboard/settings/notifications">notification preferences</a>
                    or <a style="color:#475569;text-decoration:underline;" href="%s">unsubscribe</a>.
                  </p>
                </div>
                </body>
                </html>
                """.formatted(HtmlEscape.escape(name), totalScans,
                totalOpen > 0 ? "#fca5a5" : "#e2e8f0", totalOpen,
                orgRows.toString(), FAILING_WEBSITES_LIMIT, failRows.toString(), baseUrl,
                baseUrl, unsubscribeUrl);
    }

    private String renderText(String name, List<OrgDigest> perOrg,
                              List<FailingWebsite> failing, long totalScans, long totalOpen,
                              String unsubscribeUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(name).append(",\n\n");
        sb.append("Weekly digest: ").append(totalScans).append(" scans run, ")
                .append(totalOpen).append(" open high/critical issues.\n\n");
        sb.append("Per organization:\n");
        for (OrgDigest o : perOrg) {
            sb.append("  - ").append(o.orgName()).append(": ")
                    .append(o.scans()).append(" scans, ")
                    .append(o.openIssues()).append(" open\n");
        }
        sb.append("\nCurrently failing websites:\n");
        if (failing.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (FailingWebsite f : failing) {
                sb.append("  - ").append(f.url()).append(" (")
                        .append(f.failingMonitors()).append(" failing)\n");
            }
        }
        sb.append("\nOpen dashboard: ").append(baseUrl).append("/dashboard\n\n");
        sb.append("To unsubscribe from the weekly digest: ").append(unsubscribeUrl).append("\n");
        return sb.toString();
    }

    private OrgMetrics loadMetrics(UUID orgId, Instant since) {
        long scans = scanLogRepository.countByOrganizationIdSince(orgId, since);
        long open = scanLogRepository.countOpenHighCriticalByOrganizationId(orgId);
        List<Object[]> failing = scanLogRepository
                .findFailingWebsitesByOrganizationId(orgId, FAILING_WEBSITES_LIMIT);
        return new OrgMetrics(scans, open, failing);
    }

    private record OrgDigest(String orgName, long scans, long openIssues) {
    }

    private record FailingWebsite(String url, long failingMonitors) {
    }

    /**
     * Per-org metrics cached within a single scheduler tick to avoid requerying
     * when multiple users belong to the same organization.
     */
    static final class OrgMetrics {
        final long scans;
        final long openIssues;
        final List<Object[]> failingWebsites;

        OrgMetrics(long scans, long openIssues, List<Object[]> failingWebsites) {
            this.scans = scans;
            this.openIssues = openIssues;
            this.failingWebsites = failingWebsites;
        }
    }
}
