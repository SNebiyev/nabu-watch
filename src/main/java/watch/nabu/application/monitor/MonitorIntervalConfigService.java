package watch.nabu.application.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.MonitorTypeIntervalConfig;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.infrastructure.persistence.MonitorTypeIntervalConfigRepository;

import java.util.List;

/**
 * Admin use-cases for the scheduler interval of each monitor sub-type.
 *
 * <p>Reading is unrestricted within the admin layer.  Every update immediately
 * propagates the new interval to all active monitors of that sub-type, so the
 * scheduler picks up the change on the next tick without any manual intervention.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonitorIntervalConfigService {

    private static final int SSL_FIXED_INTERVAL = 1440; // SSL is always 24 h

    private final MonitorTypeIntervalConfigRepository configRepository;
    private final ScanLimitsProperties scanLimits;

    // ── Interval config ────────────────────────────────────────────────────────

    /**
     * Returns every {@link MonitorTypeIntervalConfig} row — one per sub-type.
     */
    public List<MonitorTypeIntervalConfig> getAllIntervalConfigs() {
        return configRepository.findAll();
    }

    /**
     * Resolves the scheduler interval for a given sub-type.
     *
     * <p>SSL monitors always return {@value #SSL_FIXED_INTERVAL} regardless of
     * the config table.  For other sub-types the configured value is returned,
     * falling back to {@code nabu.scan.default-interval-minutes} if no row
     * exists yet.
     *
     * @param subType the monitor sub-type
     * @return interval in minutes
     */
    public int resolveInterval(SubType subType) {
        if (subType == SubType.SSL) {
            return SSL_FIXED_INTERVAL;
        }
        return configRepository
                .findById(subType.name())
                .map(MonitorTypeIntervalConfig::getAutoScanIntervalMinutes)
                .orElseGet(scanLimits::defaultIntervalMinutes);
    }

    /**
     * Updates the interval for a single sub-type and immediately propagates it
     * to all currently active matching monitors.
     *
     * <p>SSL updates are silently ignored — that interval is always fixed.
     *
     * @param subTypeCode     the {@link SubType} name
     * @param intervalMinutes the new interval in minutes (must be ≥ 1)
     * @throws IllegalArgumentException if intervalMinutes is less than 1
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public MonitorTypeIntervalConfig updateInterval(String subTypeCode, int intervalMinutes) {
        if (intervalMinutes < 1) {
            throw new IllegalArgumentException("intervalMinutes must be >= 1, got: " + intervalMinutes);
        }

        // SSL interval is immutable
        if ("SSL".equals(subTypeCode)) {
            log.warn("[interval-config] Attempted to update SSL interval — ignored");
            return configRepository.findById(subTypeCode)
                    .orElseGet(() -> MonitorTypeIntervalConfig.builder()
                            .subTypeCode(subTypeCode)
                            .autoScanIntervalMinutes(SSL_FIXED_INTERVAL).build());
        }

        MonitorTypeIntervalConfig config = configRepository
                .findById(subTypeCode)
                .orElseGet(() -> MonitorTypeIntervalConfig.builder()
                        .subTypeCode(subTypeCode).build());

        config.setAutoScanIntervalMinutes(intervalMinutes);
        MonitorTypeIntervalConfig saved = configRepository.save(config);

        // Propagate to live monitors
        SubType subType;
        try {
            subType = SubType.valueOf(subTypeCode);
        } catch (IllegalArgumentException e) {
            log.warn("[interval-config] Unknown subTypeCode='{}' — skipping propagation", subTypeCode);
            return saved;
        }

        int updated = configRepository.propagateIntervalToMonitors(subType, intervalMinutes);

        // If the new interval is shorter, some monitors may now be immediately
        // overdue (last_run_at + new_interval < NOW()). Reset their last_run_at
        // to NOW() so they are scheduled in `intervalMinutes` from now rather
        // than triggering an instant bulk-scan storm on the next scheduler tick.
        int reset = configRepository.resetLastRunAtForOverdueMonitors(subTypeCode, intervalMinutes);

        log.info("[interval-config] updated: subType={} interval={}m monitorsUpdated={} lastRunAtReset={}",
                subTypeCode, intervalMinutes, updated, reset);
        return saved;
    }
}
