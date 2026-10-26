package watch.nabu.infrastructure.web.dto;

import java.math.BigDecimal;

public record ComplianceTrendBucket(
        String period,
        long totalScans,
        long passingCount,
        long failingCount,
        BigDecimal passRate
) {
}
