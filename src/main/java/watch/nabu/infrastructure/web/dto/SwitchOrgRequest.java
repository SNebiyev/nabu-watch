package watch.nabu.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/auth/switch-org}.
 */
public record SwitchOrgRequest(
        @NotNull UUID organizationId
) {
}
