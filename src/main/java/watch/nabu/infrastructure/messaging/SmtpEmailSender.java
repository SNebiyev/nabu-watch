package watch.nabu.infrastructure.messaging;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageSenderPort;

/**
 * SMTP-based email sender. Works with any SMTP server:
 * Mailpit (local dev), AWS SES, SendGrid, etc.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements MessageSenderPort {

    private final JavaMailSender mailSender;

    @Value("${nabu.mail.from}")
    private String fromAddress;

    @Override
    public boolean supports(Message.Channel channel) {
        return channel == Message.Channel.EMAIL;
    }

    @Override
    public void send(Message message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(message.recipient());
            helper.setSubject(message.subject());
            helper.setText(message.textBody(), message.htmlBody());

            if (message.metadata() != null) {
                String replyTo = message.metadata().get("replyTo");
                if (replyTo != null) helper.setReplyTo(replyTo);
            }

            mailSender.send(mime);
            log.info("[email] Sent '{}' to {}", message.subject(), message.recipient());
        } catch (MessagingException e) {
            log.error("[email] Failed to send '{}' to {}: {}", message.subject(), message.recipient(), e.getMessage());
            throw new RuntimeException("Email send failed", e);
        }
    }
}
