package watch.nabu.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A direct message exchanged between two platform users.
 *
 * <p>{@code senderId} and {@code receiverId} store the user's e-mail address,
 * which is also the STOMP principal name, allowing real-time delivery via
 * {@code convertAndSendToUser()} without an extra DB lookup.
 *
 * <p>No FK constraint is imposed on these columns: message history is
 * intentionally preserved even if the sender or receiver is later deleted,
 * so the audit trail is never lost.
 *
 * <p>Messages that contain malicious payloads are never persisted — they are
 * rejected by {@link watch.nabu.application.messaging.MessageSecurityValidator}
 * before reaching this layer.
 */
@Entity
@Table(
        name = "internal_messages",
        indexes = {
                @Index(name = "idx_internal_messages_receiver_id", columnList = "receiver_id"),
                @Index(name = "idx_internal_messages_receiver_unread", columnList = "receiver_id, is_read"),
                @Index(name = "idx_internal_messages_sender_id", columnList = "sender_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternalMessage extends BaseEntity {

    /**
     * E-mail of the user who sent the message.
     */
    @Column(name = "sender_id", nullable = false, length = 255)
    private String senderId;

    /**
     * E-mail of the intended recipient.
     */
    @Column(name = "receiver_id", nullable = false, length = 255)
    private String receiverId;

    /**
     * Validated, safe message body. Never contains XSS or SQLi payloads.
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
