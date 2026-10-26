package watch.nabu.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global (instance-wide) scan and website limits. These protect third-party
 * websites and the server; they are the same for every user.
 *
 * @param domainCooldownMinutes             minimum gap between scans of one hostname
 * @param manualRescanCooldownMinutes       minimum gap between manual rescans of one website
 * @param defaultIntervalMinutes            scheduler interval when no {@code monitor_type_interval_config} row exists
 * @param scanHistoryLimit                  max scan-log entries returned per website
 * @param pendingVerificationLimit          max unverified websites per user within the window (0 = unlimited)
 * @param pendingVerificationWindowDays     window for {@code pendingVerificationLimit}
 */
@Component
public record ScanLimitsProperties(
        int domainCooldownMinutes,
        int manualRescanCooldownMinutes,
        int defaultIntervalMinutes,
        int scanHistoryLimit,
        int pendingVerificationLimit,
        int pendingVerificationWindowDays) {

    public ScanLimitsProperties(
            @Value("${nabu.scan.domain-cooldown-minutes:15}") int domainCooldownMinutes,
            @Value("${nabu.scan.manual-rescan-cooldown-minutes:30}") int manualRescanCooldownMinutes,
            @Value("${nabu.scan.default-interval-minutes:1440}") int defaultIntervalMinutes,
            @Value("${nabu.scan-history.limit:90}") int scanHistoryLimit,
            @Value("${nabu.websites.pending-verification-limit:5}") int pendingVerificationLimit,
            @Value("${nabu.websites.pending-verification-window-days:30}") int pendingVerificationWindowDays) {
        this.domainCooldownMinutes = domainCooldownMinutes;
        this.manualRescanCooldownMinutes = manualRescanCooldownMinutes;
        this.defaultIntervalMinutes = defaultIntervalMinutes;
        this.scanHistoryLimit = scanHistoryLimit;
        this.pendingVerificationLimit = pendingVerificationLimit;
        this.pendingVerificationWindowDays = pendingVerificationWindowDays;
    }
}
