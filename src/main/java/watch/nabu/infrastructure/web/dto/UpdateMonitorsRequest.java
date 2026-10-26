package watch.nabu.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code PUT /api/v1/websites/{id}/monitors}.
 *
 * <p>{@code enabledSubTypes} is the complete desired set of active monitor
 * sub-types after the update.  Any existing COMPLIANCE monitor not in this
 * list will be soft-deactivated.  The SSL monitor is always kept active and
 * does not need to be included (though it is accepted and ignored).
 *
 * <p>Examples:
 * <pre>
 * { "enabledSubTypes": ["SSL", "PRIVACY_POLICY", "COOKIE_POLICY"] }
 * { "enabledSubTypes": ["PRIVACY_POLICY"] }   // disables everything else
 * { "enabledSubTypes": [] }                   // disables all compliance monitors
 * </pre>
 */
public record UpdateMonitorsRequest(

    @NotNull(message = "enabledSubTypes is required")
    @Size(max = 10, message = "enabledSubTypes must not exceed 10 entries")
    List<String> enabledSubTypes

) {}
