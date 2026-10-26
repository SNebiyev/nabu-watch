package watch.nabu.application.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.User;

import java.time.Instant;

/**
 * Sends an internal notification email to the addresses listed in
 * {@code nabu.mail.admin-notify} whenever a new user signs up.
 * Best-effort — failures are logged but never propagated to the signup flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSignupNotifier {

    private final MessageDispatcher messageDispatcher;

    @Value("${nabu.mail.admin-notify:}")
    private String adminNotifyEmails;

    public void notifyOfSignup(User user, String source) {
        if (adminNotifyEmails == null || adminNotifyEmails.isBlank()) return;

        String email = user.getEmail();
        String name = user.getFullName();
        String userId = String.valueOf(user.getId());
        String registeredAt = Instant.now().toString();

        String subject = "New nabu-watch signup: " + email;
        String text = "A new user has registered.\n\n"
                + "Email: " + email + "\n"
                + "Name:  " + name + "\n"
                + "User ID: " + userId + "\n"
                + "Source: " + source + "\n"
                + "Registered at: " + registeredAt + "\n";

        // HTML-escape every user-controlled field — name/email come from the
        // signup form and must not be able to inject markup into an internal email.
        String html = "<p>A new user has registered.</p>"
                + "<ul>"
                + "<li><strong>Email:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>Name:</strong> " + HtmlUtils.htmlEscape(name) + "</li>"
                + "<li><strong>User ID:</strong> " + HtmlUtils.htmlEscape(userId) + "</li>"
                + "<li><strong>Source:</strong> " + HtmlUtils.htmlEscape(source) + "</li>"
                + "<li><strong>Registered at:</strong> " + HtmlUtils.htmlEscape(registeredAt) + "</li>"
                + "</ul>";

        for (String raw : adminNotifyEmails.split(",")) {
            String to = raw.trim();
            if (to.isEmpty()) continue;
            try {
                messageDispatcher.dispatch(Message.email(to, subject, html, text));
            } catch (Exception e) {
                log.warn("[admin-notify] Signup notification to {} failed: {}", to, e.getMessage());
            }
        }
    }
}
