package watch.nabu.application.compliance;

import java.util.List;

/**
 * Cookie-banner keyword payload sent to the Go worker for a COOKIE_BANNER scan.
 *
 * <p>Each list is never {@code null} — callers may iterate safely without null
 * checks. An empty list signals "no keywords of that type for this region";
 * the Go worker interprets all four lists empty as "fall back to hardcoded
 * defaults".
 *
 * @param bannerPhrases phrases whose presence in a DOM node signals a banner
 * @param acceptTerms   button labels meaning "accept cookies"
 * @param rejectTerms   button labels meaning "reject / decline cookies"
 * @param prefTerms     button labels meaning "open preferences / settings"
 */
public record BannerKeywordPayload(
        List<String> bannerPhrases,
        List<String> acceptTerms,
        List<String> rejectTerms,
        List<String> prefTerms
) {
    public boolean isEmpty() {
        return bannerPhrases.isEmpty()
                && acceptTerms.isEmpty()
                && rejectTerms.isEmpty()
                && prefTerms.isEmpty();
    }
}
