package watch.nabu.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PUT /api/v1/admin/monitor-settings/intervals}.
 */
public record UpdateIntervalConfigRequest(
        @NotBlank String subTypeCode,
        @Min(1) int intervalMinutes
) {
}
