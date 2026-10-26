package watch.nabu.infrastructure.web.dto;

import java.util.Map;

/**
 * Response body for {@code POST /api/v1/admin/monitors/sync}.
 *
 * @param websitesScanned  total number of active/paused websites examined
 * @param monitorsCreated  number of missing monitors that were (or would be) created
 * @param dryRun           {@code true} when no changes were persisted
 * @param createdBySubType breakdown of created monitors by sub-type name
 */
public record MonitorSyncResponse(
        int websitesScanned,
        int monitorsCreated,
        boolean dryRun,
        Map<String, Integer> createdBySubType
) {
}
