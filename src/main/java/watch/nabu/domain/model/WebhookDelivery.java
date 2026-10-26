package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Delivery log entry for one outbound webhook attempt.
 *
 * <p>Created with status {@code PENDING} just before the first HTTP attempt.
 * The retry scheduler picks up {@code FAILED} rows where
 * {@code nextRetryAt <= now()} and {@code attemptCount < MAX_ATTEMPTS}.
 */
@Entity
@Table(name = "webhook_deliveries",
        indexes = {
                @Index(name = "idx_webhook_deliveries_config_id", columnList = "webhook_config_id"),
                @Index(name = "idx_webhook_deliveries_retry", columnList = "next_retry_at, status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery extends BaseEntity {

    public static final int MAX_ATTEMPTS = 3;

    @Column(name = "webhook_config_id", nullable = false)
    private UUID webhookConfigId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public enum DeliveryStatus {
        PENDING, DELIVERED, FAILED
    }
}
