package watch.nabu.application.abuse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep that expires UPHELD abuse reports whose 48h ownership-proof
 * deadline has passed. Deferred execution is acceptable — the deadline is
 * in hours, a one-hour granularity is fine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbuseReportScheduler {

    private final AbuseAdminService abuseAdminService;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void runTick() {
        int removed = abuseAdminService.sweepExpired();
        if (removed > 0) {
            log.warn("[abuse-scheduler] tick: siteRemoved={}", removed);
        }
    }
}
