package watch.nabu.infrastructure.web.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import watch.nabu.application.website.CreateWebsiteCommand;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.web.UrlUtils;

import java.util.List;
import java.util.Objects;

/**
 * Request body for {@code POST /api/v1/websites}.
 *
 * <p>The authenticated user's identity is resolved server-side from the JWT;
 * it is intentionally absent here to prevent user-impersonation attacks.
 *
 * <p>Field names match the JSON keys sent by the frontend:
 * <ul>
 *   <li>{@code url}    — web address; protocol and trailing slashes are normalised
 *                        in {@link #toCommand()} via {@link UrlUtils#normalize}</li>
 *   <li>{@code region} — 2–10-character code: ISO alpha-2 (e.g. {@code "DE"})
 *                        or the sentinel value {@code "CUSTOM"}</li>
 *   <li>{@code selectedMonitors} — only consulted when {@code region == "CUSTOM"};
 *                        the subset of compliance sub-types the user wants to track</li>
 * </ul>
 */
public record CreateWebsiteRequest(

    @NotBlank(message = "url is required")
    @Size(max = 2048, message = "url must not exceed 2048 characters")
    @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
    String url,

    @NotBlank(message = "region is required")
    @Size(min = 2, max = 10, message = "region must be 2–10 characters")
    String region,

    /**
     * Optional explicit scan target for the SSL monitor.
     * When omitted, the worker uses the normalised {@code url}.
     */
    @Nullable
    @Size(max = 512, message = "sslTargetUrl must not exceed 512 characters")
    @Pattern(regexp = "https?://.+|", message = "sslTargetUrl must start with http:// or https://")
    String sslTargetUrl,

    /**
     * Optional explicit scan target for the COMPLIANCE monitors.
     * When omitted, the worker fetches the homepage ({@code url}).
     */
    @Nullable
    @Size(max = 512, message = "complianceTargetUrl must not exceed 512 characters")
    @Pattern(regexp = "https?://.+|", message = "complianceTargetUrl must start with http:// or https://")
    String complianceTargetUrl,

    /**
     * Explicit compliance monitor list.  Only consumed when {@code region == "CUSTOM"}.
     * Each string must be a valid {@link Monitor.SubType} name (case-insensitive).
     * Unknown values are silently ignored.
     */
    @Nullable
    @Size(max = 10, message = "selectedMonitors must not exceed 10 entries")
    List<String> selectedMonitors,

    /**
     * Confirmation that the caller owns the site or is allowed to scan it.
     * Validated only; deliberately not stored and not part of the command.
     */
    @AssertTrue(message = "You must confirm you are allowed to scan this website")
    boolean ownershipAttestation

) {
    public CreateWebsiteCommand toCommand() {

        // Map the optional selectedMonitors strings to SubType enums, ignoring unknowns.
        List<Monitor.SubType> subTypes = null;
        if (selectedMonitors != null && !selectedMonitors.isEmpty()) {
            subTypes = selectedMonitors.stream()
                .<Monitor.SubType>map(s -> {
                    try {
                        return Monitor.SubType.valueOf(s.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        }

        return new CreateWebsiteCommand(
            UrlUtils.normalize(url),
            region.toUpperCase(),   // "de" → "DE" etc.
            sslTargetUrl,
            complianceTargetUrl,
            subTypes
        );
    }
}
