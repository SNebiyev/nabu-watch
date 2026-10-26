package watch.nabu.application.messaging;

import java.util.Map;

/**
 * Channel-agnostic message representation.
 * Supports EMAIL today; SMS/PUSH can be added by implementing {@link MessageSenderPort}.
 */
public record Message(
        Channel channel,
        String recipient,
        String subject,
        String htmlBody,
        String textBody,
        Map<String, String> metadata
) {
    public static Message email(String to, String subject, String html, String text) {
        return new Message(Channel.EMAIL, to, subject, html, text, Map.of());
    }

    public enum Channel {EMAIL, SMS, PUSH}
}
