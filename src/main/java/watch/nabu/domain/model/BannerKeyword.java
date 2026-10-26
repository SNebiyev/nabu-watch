package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single keyword the Go worker's banner scanner uses to recognize a cookie
 * consent banner and classify its buttons as Accept, Reject, or Preferences.
 *
 * <p>Rows are scoped by {@link #region} and {@link #keywordType}. Every scan
 * receives the website region's own rows plus the rows belonging to the
 * GLOBAL region (English fallback applied everywhere).
 *
 * <p>Previously these keywords were hardcoded in Go
 * (nabu-worker/internal/scanner/banner.go); moving them to the database lets
 * admins add language coverage without redeploying the worker.
 */
@Entity
@Table(name = "banner_keywords")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BannerKeyword extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_banner_keywords_region_id"))
    private ComplianceRegion region;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword_type", nullable = false, length = 20)
    private KeywordType keywordType;

    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    /**
     * Optional ISO 639-1 language code (e.g. {@code "pt"}, {@code "de"}).
     * Informational only — matching is done by {@link #keywordType} and region.
     */
    @Column(name = "language_code", length = 5)
    private String languageCode;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    public enum KeywordType {
        /** A phrase whose presence anywhere in a DOM node indicates a cookie banner. */
        BANNER_PHRASE,
        /** A button label that means "accept cookies". */
        ACCEPT,
        /** A button label that means "reject / decline cookies". */
        REJECT,
        /** A button label that opens a cookie preferences / settings panel. */
        PREF
    }
}
