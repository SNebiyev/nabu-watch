package watch.nabu.application.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.DomainMismatchException;
import watch.nabu.application.exception.MonitorNotFoundException;
import watch.nabu.application.ratelimit.RateLimitPort;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.domain.model.Monitor;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.redis.TaskProducer;
import watch.nabu.infrastructure.web.dto.MonitorResponse;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitorRepository monitorRepository;
    private final TaskProducer taskProducer;
    private final RateLimitPort rateLimitPort;
    private final ScanBatchAggregatorPort scanBatchAggregator;
    private final ScanLimitsProperties scanLimits;

    /**
     * Verifies the caller's organization owns the given monitor.
     *
     * @throws MonitorNotFoundException if the monitor does not exist <b>or</b>
     *                                  belongs to a different organization (prevents UUID enumeration)
     */
    @Transactional(readOnly = true)
    public void requireAccess(UUID monitorId, UUID orgId) {
        UUID monitorOrgId = monitorRepository.findOrganizationIdById(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        if (!monitorOrgId.equals(orgId)) {
            throw new MonitorNotFoundException(monitorId);
        }
    }

    private static void assertTargetDomainAllowed(String websiteUrl, String targetUrl) {
        try {
            String websiteHost = new URI(websiteUrl).getHost().toLowerCase();
            String targetHost = new URI(targetUrl).getHost().toLowerCase();
            if (!targetHost.equals(websiteHost) && !targetHost.endsWith("." + websiteHost)) {
                throw new DomainMismatchException(websiteHost, targetHost);
            }
        } catch (DomainMismatchException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainMismatchException("(invalid url)", targetUrl);
        }
    }

    /**
     * Updates the {@code targetUrl} of an existing monitor.
     *
     * <p>Pass a valid URL to pin the scan target manually ({@code MANUAL}).
     * Pass {@code null} or blank to clear it and revert to the website's
     * primary URL ({@code AUTO}).
     *
     * <p>The change takes effect on the monitor's <em>next</em> scheduled run;
     * no immediate scan is triggered.
     *
     * @param monitorId the monitor to update
     * @param targetUrl the new target URL, or {@code null} / blank to reset
     * @return the updated monitor state
     * @throws MonitorNotFoundException if no monitor with the given ID exists
     */
    @Transactional
    public MonitorResponse updateTargetUrl(UUID monitorId, String targetUrl) {
        Monitor monitor = monitorRepository.findById(monitorId)
            .orElseThrow(() -> new MonitorNotFoundException(monitorId));

        // Normalise: blank → null so the worker reliably falls back to websiteUrl
        String normalized = (targetUrl != null && !targetUrl.isBlank()) ? targetUrl.strip() : null;

        if (normalized != null) {
            assertTargetDomainAllowed(monitor.getWebsite().getUrl(), normalized);
        }

        monitor.setTargetUrl(normalized);
        // No explicit save() needed — the managed entity is flushed at transaction commit.

        log.info("Monitor targetUrl updated: monitorId={} targetUrl={} source={}",
            monitorId, normalized, normalized != null ? "MANUAL" : "AUTO");

        return MonitorResponse.from(monitor);
    }

    /**
     * Immediately re-enqueues a single monitor for scanning.
     *
     * <p>Used by the per-monitor "Re-scan" button in the UI so the user can
     * refresh one check (e.g. Privacy Policy) without triggering the full
     * website rescan and wasting worker resources on SSL or other monitors.
     *
     * @param monitorId the monitor to re-enqueue
     * @param callerId  the UUID of the requesting user (for per-user rate limiting)
     * @param force     when {@code true}, bypasses rate-limit and domain-cooldown checks
     * @throws MonitorNotFoundException if no monitor with the given ID exists
     */
    @Transactional
    public void rescanMonitor(UUID monitorId, UUID callerId, boolean force) {
        Monitor monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));

        if (!force) {
            rateLimitPort.checkUserRateLimit(callerId);
            rateLimitPort.checkDomainCooldown(monitor.getWebsite().getUrl());
        }
        UUID batchId = UUID.randomUUID();
        scanBatchAggregator.registerBatch(
                batchId, 1,
                monitor.getWebsite().getId(), monitor.getWebsite().getUrl(),
                callerId, "MANUAL");
        taskProducer.enqueue(monitor, batchId);
        if (!force) {
            rateLimitPort.setDomainCooldown(
                    monitor.getWebsite().getUrl(),
                    Duration.ofMinutes(scanLimits.domainCooldownMinutes()));
        }
        log.info("Individual monitor rescan triggered: monitorId={} type={} subType={} batchId={} force={}",
                monitorId, monitor.getType(), monitor.getSubType(), batchId, force);
    }
}
