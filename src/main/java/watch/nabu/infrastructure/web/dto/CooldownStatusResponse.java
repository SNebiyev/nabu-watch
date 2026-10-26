package watch.nabu.infrastructure.web.dto;

/**
 * Returned by {@code GET /api/v1/websites/{id}/cooldown}.
 *
 * <p>Provides remaining cooldown times so the frontend can display countdowns
 * <em>before</em> the user attempts a rescan, rather than relying on 429 errors.
 *
 * @param batchCooldownSeconds  seconds remaining on the website-level manual rescan cooldown;
 *                              0 when the cooldown has elapsed (rescan allowed)
 * @param domainCooldownSeconds seconds remaining on the per-domain single-monitor cooldown;
 *                              0 when no domain cooldown is active
 */
public record CooldownStatusResponse(
        long batchCooldownSeconds,
        long domainCooldownSeconds
) {
}
