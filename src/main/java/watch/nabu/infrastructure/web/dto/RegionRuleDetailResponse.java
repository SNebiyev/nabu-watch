package watch.nabu.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

public record RegionRuleDetailResponse(
        UUID ruleId,
        UUID monitorTypeId,
        String subTypeCode,
        String defaultName,
        String description,
        boolean active,
        boolean mandatory,
        List<String> mandatoryKeywords,
        List<String> optionalKeywords
) {
}
