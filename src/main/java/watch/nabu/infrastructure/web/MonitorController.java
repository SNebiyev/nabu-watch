package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.monitor.MonitorService;
import watch.nabu.application.security.MfaValidationService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.web.dto.MonitorResponse;
import watch.nabu.infrastructure.web.dto.UpdateMonitorRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/monitors")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final SecurityContextService securityContext;
    private final MfaValidationService mfaValidationService;

    /**
     * PATCH /api/v1/monitors/{id}
     *
     * Updates the scan {@code targetUrl} for an existing monitor.
     *
     * <ul>
     *   <li>Provide a URL → monitor switches to {@code MANUAL} mode and will
     *       scan that URL on its next run.</li>
     *   <li>Send {@code null} or omit {@code targetUrl} → monitor resets to
     *       {@code AUTO} mode and falls back to the website's primary URL.</li>
     * </ul>
     *
     * <p>Responds with {@code 404 Not Found} when the monitor does not exist.
     */
    @PatchMapping("/{id}")
    public MonitorResponse updateTargetUrl(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateMonitorRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        monitorService.requireAccess(id, securityContext.resolveOrgId(principal.getUsername()));
        return monitorService.updateTargetUrl(id, request.targetUrl());
    }

    /**
     * POST /api/v1/monitors/{id}/scan
     * <p>
     * Immediately re-enqueues the specified monitor for scanning, bypassing
     * the normal interval schedule.  Responds {@code 202 Accepted} — the
     * result arrives asynchronously; the caller should poll
     * {@code GET /api/v1/websites/{id}/latest-scans} to observe the outcome.
     *
     * <p>Responds with {@code 404 Not Found} when the monitor does not exist.
     *
     * <p>Rate limiting: 5 rescans per minute per user; 1-hour domain cooldown.
     * Admin users may bypass both with {@code ?force=true} + {@code X-MFA-Token} header.
     */
    @PostMapping("/{id}/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rescan(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader(name = "X-MFA-Token", required = false) String mfaToken,
            @AuthenticationPrincipal UserDetails principal) {
        if (force) {
            mfaValidationService.validateForce(principal.getUsername(), mfaToken);
        }
        User caller = securityContext.resolveUser(principal.getUsername());
        monitorService.requireAccess(id, caller.getCurrentOrganizationId());
        monitorService.rescanMonitor(id, caller.getId(), force);
    }
}
