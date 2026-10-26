package watch.nabu.application.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.application.scan.ScanResultEvent;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends an immediate alert email when a scan result regresses into
 * {@link Severity#HIGH} or {@link Severity#CRITICAL}.
 *
 * <h3>Trigger semantics</h3>
 * <p>An email is emitted only on transitions <em>into</em> HIGH/CRITICAL. If
 * the monitor's immediately-preceding scan already had the same severity the
 * alert is suppressed, so repeated bad scans never spam opted-in recipients.
 * This uses {@code scan_logs.previous_severity} which
 * {@code ScanResultProcessor} populates for every row.
 *
 * <h3>Opt-in</h3>
 * <p>Delivery is gated per-user by {@code User.emailAlertsEnabled}. All
 * organization members who have the flag set receive the alert.
 *
 * <h3>Delivery</h3>
 * <p>This listener is fire-and-forget: it hooks the
 * {@link TransactionPhase#AFTER_COMMIT} phase of {@code ScanResultEvent} and
 * runs on the async executor so scan-result persistence is never blocked by
 * SMTP latency. Exceptions during send are logged and swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanAlertEmailService {

    private final ScanLogRepository scanLogRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrgNotificationSettingsService orgNotificationSettingsService;
    private final MessageDispatcher messageDispatcher;
    private final UnsubscribeTokenService unsubscribeTokenService;

    @Value("${nabu.mail.base-url:http://localhost:3000}")
    private String baseUrl;

    /**
     * A regression is any transition that lands in HIGH/CRITICAL from a state
     * that wasn't already HIGH/CRITICAL. First-ever failing scans
     * ({@code previous == null}) count as regressions.
     */
    private static boolean isRegression(Severity previous, Severity current) {
        if (current == null || !current.isFailing()) return false;
        if (previous == null) return true;
        return !previous.isFailing();
    }

    /**
     * AFTER_COMMIT listener — fires once the scan result transaction is durable.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScanResult(ScanResultEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            log.warn("[scan-alert] Failed to process scan alert for scanLogId={}: {}",
                    event.getScanLogId(), e.getMessage());
        }
    }

    /**
     * Package-private entry point exposed for unit tests. Performs all gating
     * (severity + regression + opt-in) and dispatches one email per opted-in
     * organization member.
     */
    @Transactional(readOnly = true)
    public void handle(ScanResultEvent event) {
        Severity current = event.getSeverity();
        if (current == null || !current.isFailing()) {
            return;
        }
        if (event.getOrganizationId() == null) {
            return;
        }

        // Resolve the ScanLog to read previousSeverity — the event carries
        // only the current severity, not the transition.
        ScanLog scanLog = scanLogRepository.findById(event.getScanLogId()).orElse(null);
        if (scanLog == null) {
            log.debug("[scan-alert] scanLog={} not found, skipping", event.getScanLogId());
            return;
        }

        Severity previous = scanLog.getPreviousSeverity();
        if (!isRegression(previous, current)) {
            log.debug("[scan-alert] no regression (prev={} curr={}), skipping scanLogId={}",
                    previous, current, event.getScanLogId());
            return;
        }

        List<OrganizationMember> members = organizationMemberRepository
                .findActiveByOrganizationIdWithUser(event.getOrganizationId());

        // Owner-level flags: only users whose org-level alertsEnabled=true
        Set<UUID> orgAlertEnabled = orgNotificationSettingsService
                .getAlertEnabledUserIds(event.getOrganizationId());

        int sent = 0;
        for (OrganizationMember member : members) {
            var user = member.getUser();
            // AND logic: both org-level (owner) AND user-level flags must be true
            if (user == null || !user.isEmailAlertsEnabled()
                    || !orgAlertEnabled.contains(user.getId())) {
                continue;
            }
            String email = user.getEmail();
            String name = user.getFullName() != null ? user.getFullName() : email;
            String subject = "[Nabu Watch] " + current.name() + " alert · " + event.getWebsiteUrl();
            String subTypeLabel = event.getSubType() != null ? event.getSubType().name() : "UNKNOWN";
            String unsubscribeUrl = unsubscribeTokenService.buildUnsubscribeUrl(user.getId(), "alerts");
            String html = renderHtml(name, event.getWebsiteUrl(), subTypeLabel,
                    previous, current, unsubscribeUrl);
            String text = renderText(name, event.getWebsiteUrl(), subTypeLabel,
                    previous, current, unsubscribeUrl);

            try {
                messageDispatcher.dispatch(Message.email(email, subject, html, text));
                sent++;
            } catch (Exception e) {
                log.warn("[scan-alert] dispatch failed for email={}: {}", email, e.getMessage());
            }
        }
        log.info("[scan-alert] scanLogId={} prev={} curr={} delivered={}/{} members",
                event.getScanLogId(), previous, current, sent, members.size());
    }

    private String renderHtml(String name, String websiteUrl, String subTypeLabel,
                              Severity previous, Severity current, String unsubscribeUrl) {
        String dashboardUrl = baseUrl + "/dashboard";
        String prevLabel = previous == null ? "no prior scan" : previous.name();
        String badgeColor = current == Severity.CRITICAL ? "#b91c1c" : "#dc2626";
        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background-color:#0f172a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                <div style="max-width:560px;margin:40px auto;background-color:#1e293b;border-radius:16px;border:1px solid #334155;padding:40px;">
                  <h1 style="color:#f8fafc;font-size:20px;margin:0 0 4px;">Nabu Watch</h1>
                  <p style="color:#64748b;font-size:12px;margin:0 0 24px;">Compliance monitoring alert</p>
                  <div style="display:inline-block;padding:4px 10px;background-color:%s;color:#ffffff;font-size:12px;font-weight:600;border-radius:999px;margin-bottom:16px;">%s</div>
                  <h2 style="color:#f1f5f9;font-size:18px;margin:0 0 16px;">A monitor just regressed to %s</h2>
                  <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 12px;">Hi %s,</p>
                  <p style="color:#94a3b8;font-size:14px;line-height:1.6;margin:0 0 16px;">
                    A %s check on <strong style="color:#e2e8f0;">%s</strong> just transitioned from
                    <strong style="color:#e2e8f0;">%s</strong> to <strong style="color:#fca5a5;">%s</strong>.
                    You are receiving this because you belong to the organization that owns this website
                    and have email alerts enabled.
                  </p>
                  <div style="text-align:center;margin:32px 0;">
                    <a style="display:inline-block;padding:12px 32px;background-color:#0ea5e9;color:#ffffff;font-size:14px;font-weight:600;text-decoration:none;border-radius:8px;" href="%s">
                      Open dashboard
                    </a>
                  </div>
                  <hr style="border:none;border-top:1px solid #334155;margin:24px 0 12px;"/>
                  <p style="color:#475569;font-size:11px;text-align:center;margin:0;">
                    You can disable these alerts at any time in your
                    <a style="color:#475569;text-decoration:underline;" href="%s/dashboard/settings/notifications">notification preferences</a>
                    or <a style="color:#475569;text-decoration:underline;" href="%s">unsubscribe</a>.
                  </p>
                </div>
                </body>
                </html>
                """.formatted(badgeColor, current.name(), current.name(), HtmlEscape.escape(name),
                subTypeLabel, HtmlEscape.escape(websiteUrl), prevLabel, current.name(), dashboardUrl,
                baseUrl, unsubscribeUrl);
    }

    private String renderText(String name, String websiteUrl, String subTypeLabel,
                              Severity previous, Severity current, String unsubscribeUrl) {
        String prevLabel = previous == null ? "no prior scan" : previous.name();
        return "Hi " + name + ",\n\n"
                + "A " + subTypeLabel + " check on " + websiteUrl
                + " transitioned from " + prevLabel + " to " + current.name() + ".\n\n"
                + "Open the dashboard: " + baseUrl + "/dashboard\n\n"
                + "To unsubscribe from these alerts: " + unsubscribeUrl + "\n";
    }

}
