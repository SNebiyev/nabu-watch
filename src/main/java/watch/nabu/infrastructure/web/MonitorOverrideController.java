package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.monitor.MonitorOverrideService;
import watch.nabu.application.monitor.MonitorService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.MonitorStatusOverride;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.web.dto.WebsiteHealthListItem.OverrideInfo;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/monitors/{monitorId}/override")
@RequiredArgsConstructor
public class MonitorOverrideController {

    private final MonitorOverrideService overrideService;
    private final MonitorService monitorService;
    private final SecurityContextService securityContext;

    private static OverrideResponse toResponse(MonitorStatusOverride override) {
        return new OverrideResponse(
                override.getId(),
                override.getMonitor().getId(),
                new OverrideInfo(
                        override.getBaselineSeverity().name(),
                        override.getOverrideSeverity().name(),
                        override.getOverriddenBy().getEmail(),
                        override.getOverriddenAt(),
                        override.getNote()));
    }

    /**
     * Creates or updates a severity override for the given monitor.
     * Requires OWNER role in the monitor's organization.
     */
    @PutMapping
    public OverrideResponse createOrUpdate(
            @PathVariable UUID monitorId,
            @Valid @RequestBody OverrideRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        User user = securityContext.resolveUser(principal.getUsername());
        Severity overrideSeverity = Severity.valueOf(request.overrideSeverity());

        MonitorStatusOverride override = overrideService.createOrUpdate(
                monitorId, request.scanLogId(), overrideSeverity, request.note(), user);

        return toResponse(override);
    }

    /**
     * Removes the severity override for the given monitor.
     * Requires OWNER role in the monitor's organization.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable UUID monitorId,
            @AuthenticationPrincipal UserDetails principal) {

        User user = securityContext.resolveUser(principal.getUsername());
        overrideService.remove(monitorId, user);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /**
     * Returns the active override for the given monitor, if any.
     * Visible to all authenticated organization members.
     */
    @GetMapping
    public OverrideResponse get(@PathVariable UUID monitorId,
                                @AuthenticationPrincipal UserDetails principal) {
        monitorService.requireAccess(monitorId, securityContext.resolveOrgId(principal.getUsername()));
        return overrideService.getOverride(monitorId)
                .map(MonitorOverrideController::toResponse)
                .orElse(null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public record OverrideRequest(
            @NotNull UUID scanLogId,
            @NotNull String overrideSeverity,
            @Size(max = 500) String note
    ) {
    }

    public record OverrideResponse(
            UUID id,
            UUID monitorId,
            OverrideInfo overrideInfo
    ) {
    }
}
