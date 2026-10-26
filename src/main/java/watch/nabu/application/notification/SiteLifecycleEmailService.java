package watch.nabu.application.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Transactional emails sent to a website's owner when something changes on
 * that site outside their direct action — admin-upheld abuse reports, DNS
 * verification expiring, etc.
 *
 * <p>Runs via {@code @Async} so SMTP latency never blocks the triggering
 * request (admin uphold click, scheduler tick). Delivery is gated per-user
 * by {@link User#isLifecycleEmailsEnabled()} — a separate flag from scan
 * alerts so users can silence one bucket without losing the other.
 * Failures are swallowed with a warn log — the in-app notification still
 * lands, so a bounced email never leaves the owner unaware of the change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteLifecycleEmailService {

    private final MessageDispatcher messageDispatcher;
    private final UnsubscribeTokenService unsubscribeTokenService;

    @Value("${nabu.mail.base-url:http://localhost:3000}")
    private String baseUrl;

    /** Address the owner can write to when appealing a removal. Defaults to the sender address. */
    @Value("${nabu.mail.abuse-email:noreply@localhost}")
    private String abuseEmail;

    @Async
    public void sendAbuseUpheld(User owner, Website site) {
        if (owner == null || !shouldEmail(owner)) return;
        String subject = "Website paused — abuse report upheld";
        String body = "We received an abuse report against " + site.getUrl()
                + " and paused scans pending ownership proof. You have 48 hours to prove control "
                + "(complete DNS TXT verification or contact support). Otherwise the website will "
                + "be removed.";
        dispatch(owner, site, subject, body, "Prove ownership");
    }

    @Async
    public void sendAbuseOwnershipRestored(User owner, Website site) {
        if (owner == null || !shouldEmail(owner)) return;
        String subject = "Website reactivated";
        String body = "Ownership of " + site.getUrl()
                + " was accepted. Scans have resumed and your verified badge is intact.";
        dispatch(owner, site, subject, body, "Open dashboard");
    }

    @Async
    public void sendAbuseSiteRemoved(User owner, Website site) {
        if (owner == null || !shouldEmail(owner)) return;
        String subject = "Website removed after abuse report";
        String body = site.getUrl() + " was removed after an upheld abuse report and no "
                + "ownership proof within 48 hours. If this was incorrect, contact support to appeal.";
        String encodedUrl = URLEncoder.encode(
                site.getUrl() == null ? "" : site.getUrl(), StandardCharsets.UTF_8);
        String supportMailto = "mailto:" + abuseEmail + "?subject=Appeal%20for%20removed%20website%20" + encodedUrl;
        dispatchWithHref(owner, site, subject, body, "Contact support", supportMailto);
    }

    @Async
    public void sendVerificationExpired(User owner, Website site) {
        if (owner == null || !shouldEmail(owner)) return;
        String subject = "Domain verification expired for " + site.getUrl();
        String body = "The 7-day DNS TXT verification window for " + site.getUrl() + " "
                + "expired without a successful check. Re-add the website in the dashboard to "
                + "generate a fresh challenge.";
        dispatch(owner, site, subject, body, "Open dashboard");
    }

    /**
     * Warning fired ~24 hours before the DNS TXT challenge expires, while
     * it's still actionable. Scheduler guarantees one send per challenge
     * via {@code website_verifications.expiry_warning_sent_at}.
     */
    @Async
    public void sendVerificationGraceWarning(User owner, Website site) {
        if (owner == null || !shouldEmail(owner)) return;
        String subject = "DNS verification expires soon for " + site.getUrl();
        String body = "Your DNS TXT verification window for " + site.getUrl() + " "
                + "expires in less than 24 hours. Complete the challenge now to keep your "
                + "verified badge — once the window closes you'll need to re-add the website "
                + "to generate a fresh token.";
        dispatch(owner, site, subject, body, "Open dashboard");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean shouldEmail(User user) {
        return user.isLifecycleEmailsEnabled() && user.getEmail() != null;
    }

    private void dispatch(User owner, Website site, String subject, String body, String ctaLabel) {
        dispatchWithHref(owner, site, subject, body, ctaLabel, baseUrl + "/dashboard");
    }

    private void dispatchWithHref(User owner, Website site, String subject, String body,
                                  String ctaLabel, String ctaHref) {
        try {
            String unsubscribeUrl = unsubscribeTokenService.buildUnsubscribeUrl(owner.getId(), "lifecycle");
            String html = html(owner.getFullName(), subject, body, ctaLabel, ctaHref, unsubscribeUrl);
            String text = text(owner.getFullName(), body, ctaHref, unsubscribeUrl);
            messageDispatcher.dispatch(Message.email(owner.getEmail(), subject, html, text));
        } catch (Exception e) {
            log.warn("[site-lifecycle-email] dispatch failed for userId={} site={} subject='{}': {}",
                    owner.getId(), site.getId(), subject, e.getMessage());
        }
    }

private static String html(String name, String subject, String body, String cta, String href, String unsubscribe) {
        String greeting = name != null && !name.isBlank() ? "Hi " + HtmlEscape.escape(name) + "," : "Hi,";
        return """
            <!DOCTYPE html>
            <html><body style="font-family:system-ui,sans-serif;color:#0f172a;max-width:560px;margin:0 auto;padding:24px">
              <h2 style="margin:0 0 12px;font-size:18px">%s</h2>
              <p style="margin:0 0 16px">%s</p>
              <p style="margin:0 0 24px;line-height:1.5">%s</p>
              <a href="%s" style="display:inline-block;background:#0284c7;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none;font-weight:600">%s</a>
              <hr style="margin:32px 0;border:0;border-top:1px solid #e2e8f0"/>
              <p style="color:#64748b;font-size:12px;margin:0">
                You received this because email alerts are enabled for your account.
                <a href="%s" style="color:#64748b">Unsubscribe from alerts</a>.
              </p>
            </body></html>
            """.formatted(HtmlEscape.escape(subject), greeting, HtmlEscape.escape(body), HtmlEscape.escape(href), HtmlEscape.escape(cta), HtmlEscape.escape(unsubscribe));
    }

    private static String text(String name, String body, String href, String unsubscribe) {
        String greeting = name != null && !name.isBlank() ? "Hi " + name + ",\n\n" : "Hi,\n\n";
        return greeting + body + "\n\nOpen dashboard: " + href
                + "\n\n---\nUnsubscribe: " + unsubscribe + "\n";
    }
}
