package watch.nabu.application.scan;

import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanStatusChange;

import java.util.Map;
import java.util.UUID;

/**
 * Carries all data needed to compose the aggregated SCAN_SUMMARY notification
 * once every monitor in a manual-rescan batch has reported its result.
 */
public record ScanSummaryData(
        UUID batchId,
        UUID websiteId,
        String websiteUrl,
        UUID userId,
        String triggerType,
        Map<Monitor.SubType, ScanLog.Severity> results,
        Map<Monitor.SubType, ScanStatusChange> statusChanges,
        boolean aborted
) {
}
