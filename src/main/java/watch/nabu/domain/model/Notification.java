package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted notification delivered to a user after a significant scan event.
 *
 * <p>Notifications are append-only — they are never updated except to mark
 * them as {@code read}.  The {@code userId} is stored as a plain column
 * (not a {@code @ManyToOne} join) to keep reads fast and avoid accidental
 * lazy-load exceptions outside a transaction.
 *
 * <p>Delivered to the browser via STOMP WebSocket
 * ({@code /user/{email}/queue/notifications}) and persisted here for the
 * notification-bell history panel.
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notifications_user_id", columnList = "user_id"),
                @Index(name = "idx_notifications_user_unread", columnList = "user_id, read")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "title", nullable = false, length = 255)
    private String title;
    @Column(name = "message", nullable = false, length = 1000)
    private String message;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(name = "organization_name", length = 255)
    private String organizationName;
    /**
     * ID of the related entity — typically the {@code websiteId} or
     * {@code monitorId} that triggered this notification.
     */
    @Column(name = "related_entity_id")
    private UUID relatedEntityId;
    @Column(name = "read", nullable = false)
    @Builder.Default
    private boolean read = false;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum NotificationType {
        SCAN_COMPLETED,
        SCAN_FAILED,
        CERTIFICATE_EXPIRY,
        COMPLIANCE_ISSUE,
        /**
         * Fired when a malicious payload is blocked; routed to all ADMIN users.
         */
        SECURITY_ALERT,
        /**
         * Fired when an authenticated user receives a new internal message.
         */
        NEW_MESSAGE,

        /**
         * Aggregated summary fired once when all monitors in a manual-rescan batch
         * have reported their results.  Replaces the individual per-monitor
         * SCAN_COMPLETED / COMPLIANCE_ISSUE / CERTIFICATE_EXPIRY notifications that
         * would otherwise flood the bell panel after a single "Re-scan" click.
         */
        SCAN_SUMMARY,

        /**
         * Fired when a user reports a monitor result as incorrect.
         * Delivered in real time to all ADMIN users.
         */
        MONITOR_REPORT,

        /**
         * Fired against the owner of a website when that site's lifecycle is
         * changed by an abuse-report admin action: paused, reactivated,
         * or removed.
         */
        ABUSE_REPORT,

        /**
         * Fired when a website's DNS TXT verification window expires without
         * a successful check — owner action required.
         */
        VERIFICATION_EXPIRED,

        /**
         * Pre-expiry warning (~24h before {@code expires_at}) while the
         * challenge is still actionable. Distinct from the terminal
         * {@code VERIFICATION_EXPIRED} so the UI can tell "act now" apart
         * from "window closed — re-add the site".
         */
        VERIFICATION_EXPIRING_SOON
    }
}
