package watch.nabu.application.compliance;

import java.util.List;

/**
 * Keyword payload built by {@link ComplianceTaskBuilderService} for a single
 * compliance monitor scan task.
 *
 * <p>All three lists are never {@code null} — callers may always iterate them
 * safely without null checks.
 *
 * @param requiredKeywords MANDATORY_NATIVE keywords; finding ≥ 1 = PASSING (green).
 * @param optionalKeywords OPTIONAL_NATIVE keywords; absence is silent (score boost only).
 * @param fallbackKeywords ENGLISH_FALLBACK keywords; finding these triggers
 *                         {@code language_inconsistency = true} and MEDIUM severity.
 *                         Empty for English-language regions (GB, US).
 */
public record ComplianceTaskPayload(
        List<String> requiredKeywords,
        List<String> optionalKeywords,
        List<String> fallbackKeywords
) {
    public static ComplianceTaskPayload empty() {
        return new ComplianceTaskPayload(List.of(), List.of(), List.of());
    }
}
