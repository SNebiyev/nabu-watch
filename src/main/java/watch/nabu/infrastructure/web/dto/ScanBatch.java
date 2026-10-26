package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Returned by {@code GET /api/v1/websites/{id}/scan-history}.
 *
 * <p>Represents one group of related scan logs triggered together — either a
 * full website rescan (all active monitors share one {@code batch_id}) or a
 * granular single-monitor rescan (one monitor, one {@code batch_id}).
 *
 * <p>Legacy scan logs without a {@code batch_id} are grouped dynamically by a
 * 1-minute time window: all scans in the same 60-second bucket for the same
 * website are treated as a single synthetic batch.
 *
 * @param batchId     UUID string for modern scans; "legacy-&lt;epoch60&gt;" for legacy rows
 * @param timestamp   earliest {@code created_at} in the batch — represents when the scan started
 * @param scanType    {@code "FULL"} when the batch covers all active monitors;
 *                    {@code "PARTIAL"} when only a subset was scanned
 * @param scans       individual scan-log results belonging to this batch
 * @param triggerType {@code "MANUAL"} or {@code "SCHEDULED"}; {@code null} for legacy batches
 * @param triggeredByEmail email of the user who triggered the batch; {@code null} for scheduled scans
 */
public record ScanBatch(
        String batchId,
        Instant timestamp,
        String scanType,
        List<LatestScanItem> scans,
        String triggerType,
        String triggeredByEmail
) {
}
