package watch.nabu.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code PUT /api/v1/websites/{id}/monitors}.
 *
 * <p>Reports exactly what changed so the frontend can update its UI without
 * a follow-up GET.
 *
 * @param websiteId   the website whose monitors were updated
 * @param activated   sub-types that were created or reactivated in this call
 * @param deactivated sub-types that were soft-disabled in this call
 * @param unchanged   sub-types that were already in the desired state
 */
public record SyncMonitorsResult(
    UUID         websiteId,
    List<String> activated,
    List<String> deactivated,
    List<String> unchanged
) {}
