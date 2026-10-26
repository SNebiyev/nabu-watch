package watch.nabu.infrastructure.web.dto;

import java.util.UUID;

public record ComplianceRegionResponse(
        UUID id,
        String code,
        String name,
        boolean active,
        boolean globalDefault
) {
}
