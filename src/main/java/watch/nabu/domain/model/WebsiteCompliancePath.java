package watch.nabu.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted compliance document path discovered for a website + subType pair.
 *
 * <p>Written by Go scanners (rule-based or AI) when they successfully locate a
 * compliance page URL.  Read back by {@link watch.nabu.infrastructure.redis.TaskProducer}
 * before dispatching the next scan so the worker can verify the known URL first
 * (fast-path) before falling back to a full homepage crawl + AI discovery cycle.
 *
 * <p>One row per (website_id, sub_type) — the native UPSERT in
 * {@link watch.nabu.infrastructure.persistence.WebsiteCompliancePathRepository}
 * enforces this invariant with a confidence-rank gate: a lower-confidence entry
 * will never overwrite a higher-confidence one.
 *
 * <p>{@code lastVerifiedAt} is updated directly by native SQL in the repository;
 * Hibernate does not manage it, so there is no {@code @UpdateTimestamp} here.
 */
@Entity
@Table(name = "website_compliance_paths")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebsiteCompliancePath extends BaseEntity {

    @Column(name = "website_id", nullable = false)
    private UUID websiteId;

    /**
     * Matches {@link Monitor.SubType#name()} — stored as a plain varchar.
     */
    @Column(name = "sub_type", nullable = false, length = 30)
    private String subType;

    /**
     * Absolute URL of the compliance document.
     */
    @Column(name = "discovered_url", nullable = false, columnDefinition = "text")
    private String discoveredUrl;

    /**
     * {@code href} attribute of the homepage {@code <a>} element; may be relative.
     */
    @Column(name = "anchor_href", nullable = false, columnDefinition = "text")
    private String anchorHref;

    /**
     * Visible link label text.
     */
    @Column(name = "anchor_text", nullable = false, length = 500)
    private String anchorText;

    /**
     * CSS selector for the homepage link element (nullable — for UI/debug use).
     */
    @Column(name = "dom_selector", columnDefinition = "text")
    private String domSelector;

    /**
     * Discovery method: {@code RULE_BASED}, {@code AI_SEMANTIC_VERIFIED}, or {@code AI_DISCOVERY}.
     */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    /**
     * Confidence tier: {@code high}, {@code medium}, or {@code low}.
     */
    @Column(name = "confidence", nullable = false, length = 10)
    private String confidence;

    @CreationTimestamp
    @Column(name = "discovered_at", updatable = false, nullable = false)
    private Instant discoveredAt;

    /**
     * Updated by the native UPSERT / verification queries in
     * {@link watch.nabu.infrastructure.persistence.WebsiteCompliancePathRepository} — not
     * managed by Hibernate.
     */
    @Column(name = "last_verified_at", nullable = false)
    private Instant lastVerifiedAt;

    /**
     * Number of consecutive fast-path verification failures.
     * Auto-deleted when this reaches 3 via
     * {@link watch.nabu.infrastructure.persistence.WebsiteCompliancePathRepository#deleteIfStale}.
     */
    @Column(name = "fail_count", nullable = false)
    private int failCount;
}
