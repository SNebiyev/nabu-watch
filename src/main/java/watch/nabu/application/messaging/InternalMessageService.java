package watch.nabu.application.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.MaliciousPayloadException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.domain.model.InternalMessage;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.InternalMessageRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core use-case service for the internal messaging feature.
 *
 * <h3>Zero-Trust send flow</h3>
 * <ol>
 *   <li>Content is passed through {@link MessageSecurityValidator} <em>before</em>
 *       any persistence occurs.</li>
 *   <li>If the validator throws {@link MaliciousPayloadException}:
 *       <ul>
 *         <li>All admin users are fetched from the DB.</li>
 *         <li>A real-time {@code SECURITY_ALERT} notification is sent to every admin
 *             via {@link NotificationService#sendSecurityAlertToAdmins}.</li>
 *         <li>The exception is re-thrown so the controller layer returns
 *             HTTP 400 to the sender with a generic policy message.</li>
 *       </ul>
 *   </li>
 *   <li>If the content is safe, the {@link InternalMessage} is persisted, then:
 *       <ul>
 *         <li>A raw {@link WsMessageEvent} is pushed to
 *             {@code /user/{receiverEmail}/queue/messages} so the live chat pane
 *             can append the message without polling.</li>
 *         <li>A {@code NEW_MESSAGE} bell notification is delivered to the recipient
 *             via {@link NotificationService#sendNewMessageNotification}.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalMessageService {

    private static final String MESSAGE_DESTINATION = "/queue/messages";

    private final InternalMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageSecurityValidator securityValidator;
    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Validates and sends an internal message.
     *
     * @param senderEmail   e-mail of the authenticated sender (used as senderId)
     * @param receiverEmail e-mail of the intended recipient
     * @param content       raw message body supplied by the sender
     * @return the persisted {@link InternalMessage}
     * @throws MaliciousPayloadException if the content contains a security violation
     */
    @Transactional
    public InternalMessage sendMessage(String senderEmail, String receiverEmail, String content) {
        Instant now = Instant.now();

        try {
            securityValidator.validate(content);
        } catch (MaliciousPayloadException ex) {
            log.warn("[security] malicious payload blocked: sender={} receiver={} at={}",
                    senderEmail, receiverEmail, now);
            List<User> admins = userRepository.findByRole(User.Role.ADMIN);
            notificationService.sendSecurityAlertToAdmins(senderEmail, now, admins);
            throw ex; // re-throw — controller maps this to HTTP 400
        }

        InternalMessage saved = messageRepository.save(
                InternalMessage.builder()
                        .senderId(senderEmail)
                        .receiverId(receiverEmail)
                        .content(content)
                        .build()
        );

        // 1. Push full message to the dedicated /queue/messages destination so
        //    the live chat pane can append it without a REST poll.
        WsMessageEvent event = new WsMessageEvent(
                saved.getId(),
                senderEmail,
                receiverEmail,
                content,
                saved.getCreatedAt()
        );
        messagingTemplate.convertAndSendToUser(receiverEmail, MESSAGE_DESTINATION, event);
        log.debug("[messaging] ws-message pushed: id={} receiver={}", saved.getId(), receiverEmail);

        // 2. Persist a bell notification and deliver it to /queue/notifications.
        notificationService.sendNewMessageNotification(receiverEmail, senderEmail, saved.getId());

        log.debug("[messaging] message saved: id={} sender={} receiver={}",
                saved.getId(), senderEmail, receiverEmail);
        return saved;
    }

    /**
     * Returns the paginated inbox (messages received by {@code receiverEmail}),
     * newest-first.
     */
    @Transactional(readOnly = true)
    public Page<InternalMessage> getInbox(String receiverEmail, Pageable pageable) {
        return messageRepository.findByReceiverIdOrderByCreatedAtDesc(receiverEmail, pageable);
    }

    /**
     * Returns the paginated outbox (messages sent by {@code senderEmail}),
     * newest-first.
     */
    @Transactional(readOnly = true)
    public Page<InternalMessage> getSentMessages(String senderEmail, Pageable pageable) {
        return messageRepository.findBySenderIdOrderByCreatedAtDesc(senderEmail, pageable);
    }

    /**
     * Returns the number of distinct senders with unread messages in the inbox.
     */
    @Transactional(readOnly = true)
    public int getUnreadDistinctSenderCount(String receiverEmail) {
        return messageRepository.countUnreadDistinctSenders(receiverEmail);
    }

    /**
     * Marks all messages from {@code senderEmail} to {@code receiverEmail} as read.
     */
    @Transactional
    public void markConversationAsRead(String receiverEmail, String senderEmail) {
        int updated = messageRepository.markConversationAsRead(receiverEmail, senderEmail);
        log.debug("[messaging] marked conversation read: receiver={} sender={} count={}",
                receiverEmail, senderEmail, updated);
    }

    /**
     * Immutable WebSocket event pushed to {@code /user/{email}/queue/messages}
     * after each successfully saved message.  The frontend chat pane uses this
     * to append the message instantly without a REST round-trip.
     */
    public record WsMessageEvent(
            UUID id,
            String senderId,
            String receiverId,
            String content,
            Instant createdAt
    ) {
    }
}
