package watch.nabu.infrastructure.web.dto;

import java.util.List;

public record UpsertRegionRuleRequest(
        boolean active,
        boolean mandatory,
        List<String> mandatoryKeywords,
        List<String> optionalKeywords
) {
}
