package watch.nabu.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound webhook endpoint registered for an organization.
 *
 * <p>On each scan result the delivery service iterates all enabled configs for
 * the affected organization and POSTs a JSON payload to {@code url}.
 *
 * <p>When {@code secret} is set the payload is signed with HMAC-SHA256 and the
 * signature is sent in the {@code X-Nabu-Signature} request header so that
 * receivers can verify authenticity.
 *
 * <p>Type {@code SLACK} formats the payload as a Slack incoming-webhook message
 * ({@code text} + {@code attachments}); {@code GENERIC} sends a structured JSON
 * object understood by custom integrations.
 */
@Entity
@Table(name = "webhook_configs",
        indexes = @Index(name = "idx_webhook_configs_org_id", columnList = "organization_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookConfig extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotBlank
    @Size(max = 2048)
    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Size(max = 255)
    @Column(name = "secret", length = 255)
    private String secret;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private WebhookType type = WebhookType.GENERIC;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum WebhookType {
        GENERIC, SLACK
    }
}
