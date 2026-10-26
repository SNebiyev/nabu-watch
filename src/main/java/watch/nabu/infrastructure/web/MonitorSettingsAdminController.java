package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.monitor.MonitorIntervalConfigService;
import watch.nabu.domain.model.MonitorTypeIntervalConfig;
import watch.nabu.infrastructure.web.dto.IntervalConfigResponse;
import watch.nabu.infrastructure.web.dto.UpdateIntervalConfigRequest;

import java.util.List;

/**
 * Admin endpoints for monitor interval configuration.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/admin/monitor-settings/intervals} — one config per sub-type</li>
 *   <li>{@code PUT  /api/v1/admin/monitor-settings/intervals} — update one config</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/monitor-settings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MonitorSettingsAdminController {

    private final MonitorIntervalConfigService configService;

    /**
     * Returns the interval config of every sub-type.
     */
    @GetMapping("/intervals")
    public List<IntervalConfigResponse> getIntervalConfigs() {
        return configService.getAllIntervalConfigs().stream()
                .map(c -> new IntervalConfigResponse(c.getSubTypeCode(), c.getAutoScanIntervalMinutes()))
                .toList();
    }

    /**
     * Updates a single interval config and propagates it to active monitors.
     */
    @PutMapping("/intervals")
    public IntervalConfigResponse updateIntervalConfig(
            @RequestBody @Valid UpdateIntervalConfigRequest req) {
        MonitorTypeIntervalConfig saved = configService.updateInterval(
                req.subTypeCode(), req.intervalMinutes());
        return new IntervalConfigResponse(saved.getSubTypeCode(), saved.getAutoScanIntervalMinutes());
    }
}
