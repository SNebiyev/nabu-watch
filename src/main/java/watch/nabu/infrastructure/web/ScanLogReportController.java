package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.scanlog.ScanLogReportService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User;

import java.util.UUID;

/**
 * User-facing endpoint for reporting a scan result.
 */
@RestController
@RequestMapping("/api/v1/scan-logs")
@RequiredArgsConstructor
public class ScanLogReportController {

    private final ScanLogReportService reportService;
    private final SecurityContextService securityContext;

    /**
     * POST /api/v1/scan-logs/{id}/report
     * Reports a scan result. The scan log must belong to the caller's organisation.
     * Notes are required.
     */
    @PostMapping("/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(
            @PathVariable UUID id,
            @Valid @RequestBody ReportRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        User reporter = securityContext.resolveUser(principal.getUsername());
        UUID orgId = reporter.getCurrentOrganizationId();
        reportService.report(id, orgId, reporter, request.notes());
    }

    public record ReportRequest(
            @NotBlank(message = "notes must not be blank")
            @Size(max = 2000, message = "notes must not exceed 2000 characters")
            String notes
    ) {}
}
