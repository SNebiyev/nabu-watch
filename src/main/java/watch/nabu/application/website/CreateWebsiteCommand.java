package watch.nabu.application.website;

import watch.nabu.domain.model.Monitor.SubType;

import java.util.List;

/**
 * Immutable command object for the "create website" use case.
 *
 * @param url                the normalised, protocol-prefixed URL (never null / blank)
 * @param regionCode         ISO 3166-1 alpha-2 (e.g. "DE") or the sentinel "CUSTOM"
 * @param sslTargetUrl       optional explicit scan URL for the SSL monitor
 * @param complianceTargetUrl optional explicit scan URL for COMPLIANCE monitors
 * @param selectedSubTypes   when non-null, overrides regional defaults with an
 *                           explicit monitor list (used with regionCode == "CUSTOM")
 */
public record CreateWebsiteCommand(
    String         url,
    String         regionCode,
    String         sslTargetUrl,
    String         complianceTargetUrl,
    List<SubType>  selectedSubTypes
) {
}
