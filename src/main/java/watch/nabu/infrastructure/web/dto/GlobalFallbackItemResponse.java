package watch.nabu.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

public record GlobalFallbackItemResponse(
        UUID monitorTypeId,
        String subTypeCode,
        String defaultName,
        String description,
        List<String> fallbackKeywords
) {
}
