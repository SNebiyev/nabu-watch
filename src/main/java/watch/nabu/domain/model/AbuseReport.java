package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Third-party complaint that a website is being scanned without the owner's
 * permission. Submitting one pauses the website immediately and starts a
 * 48-hour window for the user to prove ownership (typically by completing
 * DNS TXT verification on a freshly issued challenge).
 */
@Entity
@Table(name = "abuse_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbuseReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "website_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_abuse_reports_website_id"))
    private Website website;

    @NotBlank
    @Email
    @Size(max = 255)
    @Column(name = "reporter_email", nullable = false, length = 255)
    private String reporterEmail;

    @Size(max = 45)
    @Column(name = "reporter_ip", length = 45)
    private String reporterIp;

    @NotBlank
    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "proof_deadline_at", nullable = false)
    private Instant proofDeadlineAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public enum Status {
        /** Anonymous submission, not yet triaged. Website unaffected. */
        PENDING,
        /** Admin confirmed the complaint. Website paused; 48h clock running. */
        UPHELD,
        /** Owner re-verified control in time. Website reactivated. */
        OWNERSHIP_PROVEN,
        /** UPHELD deadline expired without proof. Website soft-deleted. */
        SITE_REMOVED,
        /** Admin judged the report frivolous. No change. */
        DISMISSED
    }
}
