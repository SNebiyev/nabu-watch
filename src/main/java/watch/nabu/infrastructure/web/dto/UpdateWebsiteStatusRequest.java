package watch.nabu.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import watch.nabu.domain.model.Website.WebsiteStatus;

/**
 * Request body for {@code PATCH /api/v1/websites/{id}/status}.
 *
 * @param status the desired website status; only {@code ACTIVE} and {@code PAUSED} are accepted
 */
public record UpdateWebsiteStatusRequest(
        @NotNull WebsiteStatus status
) {
}
