package watch.nabu.infrastructure.web.dto;

/**
 * Response body for {@code GET /api/v1/admin/stats}.
 *
 * @param totalUsers          total number of registered user accounts
 * @param totalWebsites       total website records regardless of status
 * @param totalMonitors       total monitor records across all websites
 * @param activeScansLastHour number of scan logs persisted in the last 60 minutes
 * @param systemFailureRate   percentage of all scans that reported a FAILED status,
 *                            rounded to two decimal places (e.g. {@code 3.14})
 */
public record AdminStatsResponse(
        long   totalUsers,
        long   totalWebsites,
        long   totalMonitors,
        long   activeScansLastHour,
        double systemFailureRate
) {}
