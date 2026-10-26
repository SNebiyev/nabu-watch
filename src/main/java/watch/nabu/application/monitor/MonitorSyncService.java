package watch.nabu.application.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.SyncMonitorsResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Application service that reconciles a website's compliance monitor set
 * against a caller-supplied desired list.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>The SSL monitor is permanent and is never touched by this service.</li>
 *   <li>Monitors are never hard-deleted; they are soft-deactivated ({@code active = false})
 *       so that historical scan data is always retained.</li>
 *   <li>Every monitor enabled in this call is immediately enqueued for a
 *       fresh scan via the Redis task stream.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorSyncService {

    private static final int COMPLIANCE_INTERVAL_MINUTES = 1440; // daily

    private final UserRepository     userRepository;
    private final WebsiteRepository  websiteRepository;
    private final MonitorRepository  monitorRepository;
    private final TaskProducer       taskProducer;

    /**
     * Reconciles the compliance monitors for {@code websiteId} against the
     * given {@code enabledSubTypes} list.
     *
     * <p>Reads the caller's identity from the Spring Security context (JWT).
     *
     * @param websiteId      the website to update
     * @param enabledSubTypes the complete desired set of active sub-types
     *                        (SSL is always active and is ignored if present)
     * @return a summary of what was activated, deactivated, or unchanged
     */
    @Transactional
    public SyncMonitorsResult sync(UUID websiteId, List<String> enabledSubTypes) {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();
        UUID userId = userRepository.findByEmail(email)
            .map(u -> u.getId())
            .orElseThrow(() -> new UserNotFoundException(email));

        Website website = websiteRepository.findById(websiteId)
            .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        // Parse requested sub-types, excluding SSL (always permanent)
        Set<SubType> requested = enabledSubTypes.stream()
            .<SubType>map(s -> {
                try { return SubType.valueOf(s.toUpperCase()); }
                catch (IllegalArgumentException e) { return null; }
            })
            .filter(Objects::nonNull)
            .filter(st -> st != SubType.SSL)   // SSL is permanent, never managed here
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        // Load all existing COMPLIANCE monitors (active + inactive)
        Map<SubType, Monitor> existing = monitorRepository
            .findByWebsiteId(websiteId)
            .stream()
            .filter(m -> m.getType() == MonitorType.COMPLIANCE)
            .collect(Collectors.toMap(Monitor::getSubType, m -> m));

        List<String> activated   = new ArrayList<>();
        List<String> deactivated = new ArrayList<>();
        List<String> unchanged   = new ArrayList<>();

        // ── Activate / create requested sub-types ───────────────────────────
        for (SubType subType : requested) {
            if (!existing.containsKey(subType)) {
                // Create a brand-new monitor
                Monitor created = buildAndSave(website, subType);
                taskProducer.enqueue(created);
                activated.add(subType.name());
                log.info("[monitor-sync] Created: websiteId={} subType={} monitorId={}",
                    websiteId, subType, created.getId());

            } else {
                Monitor m = existing.get(subType);
                if (!m.isActive()) {
                    m.setActive(true);
                    monitorRepository.save(m);
                    taskProducer.enqueue(m);
                    activated.add(subType.name());
                    log.info("[monitor-sync] Reactivated: websiteId={} subType={} monitorId={}",
                        websiteId, subType, m.getId());
                } else {
                    // Already active — just re-enqueue for a fresh scan
                    taskProducer.enqueue(m);
                    unchanged.add(subType.name());
                    log.debug("[monitor-sync] Unchanged (re-enqueued): websiteId={} subType={} monitorId={}",
                        websiteId, subType, m.getId());
                }
            }
        }

        // ── Deactivate monitors no longer in the requested set ───────────────
        for (Map.Entry<SubType, Monitor> entry : existing.entrySet()) {
            SubType subType = entry.getKey();
            if (!requested.contains(subType) && entry.getValue().isActive()) {
                entry.getValue().setActive(false);
                monitorRepository.save(entry.getValue());
                deactivated.add(subType.name());
                log.info("[monitor-sync] Deactivated: websiteId={} subType={} monitorId={}",
                    websiteId, subType, entry.getValue().getId());
            }
        }

        log.info("[monitor-sync] Complete: websiteId={} activated={} deactivated={} unchanged={}",
            websiteId, activated, deactivated, unchanged);

        return new SyncMonitorsResult(websiteId, activated, deactivated, unchanged);
    }

    private Monitor buildAndSave(Website website, SubType subType) {
        Monitor monitor = Monitor.builder()
            .website(website)
            .type(MonitorType.COMPLIANCE)
            .subType(subType)
            .intervalMinutes(COMPLIANCE_INTERVAL_MINUTES)
            .targetUrl(website.getUrl())   // base URL — worker discovers the page
            .active(true)
            .build();
        return monitorRepository.save(monitor);
    }
}
