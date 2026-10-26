package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Owner-level notification settings for a specific member within an organization.
 *
 * <p>This is one half of the two-level notification model:
 * <ul>
 *   <li><strong>Owner level</strong> (this entity) — the org owner decides whether
 *       a member <em>should</em> receive notifications for this org.</li>
 *   <li><strong>User level</strong> ({@link User#isEmailAlertsEnabled()}) — the member
 *       decides whether they <em>want</em> to receive notifications globally.</li>
 * </ul>
 *
 * <p>An email is sent only when <strong>both</strong> flags are {@code true} (AND logic).
 */
@Entity
@Table(
        name = "org_notification_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_org_notif_org_user",
                columnNames = {"organization_id", "user_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgNotificationSetting extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "organization_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_org_notif_org_id")
    )
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_org_notif_user_id")
    )
    private User user;

    @Column(name = "alerts_enabled", nullable = false)
    @Builder.Default
    private boolean alertsEnabled = false;

    @Column(name = "digest_enabled", nullable = false)
    @Builder.Default
    private boolean digestEnabled = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
