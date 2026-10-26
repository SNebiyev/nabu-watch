package watch.nabu.infrastructure.web.dto;

import java.util.UUID;

public record ComplianceMonitorTypeResponse(
        UUID id,
        String subTypeCode,
        String defaultName,
        String description
) {
}
