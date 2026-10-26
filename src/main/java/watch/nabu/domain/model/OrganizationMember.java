package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Maps a {@link User} to an {@link Organization} with a role.
 *
 * <p>A user may belong to multiple organizations, and each organization
 * may have multiple members.  The unique constraint on
 * {@code (organization_id, user_id)} prevents duplicate memberships.
 */
@Entity
@Table(
        name = "organization_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_org_members_org_user",
                columnNames = {"organization_id", "user_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "organization_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_org_members_org_id")
    )
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_org_members_user_id")
    )
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.MEMBER;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    public enum Role {
        OWNER, MEMBER
    }
}
