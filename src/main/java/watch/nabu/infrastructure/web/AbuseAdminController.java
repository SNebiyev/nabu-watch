package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.abuse.AbuseAdminService;
import watch.nabu.domain.model.AbuseReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin triage endpoints for abuse reports. */
@RestController
@RequestMapping("/api/v1/admin/abuse/reports")
@RequiredArgsConstructor
public class AbuseAdminController {

    private final AbuseAdminService service;

    @GetMapping
    public AbuseReportPageResponse list(
            @RequestParam(required = false) AbuseReport.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<AbuseReport> rows = service.list(status, pageable);
        return new AbuseReportPageResponse(
                rows.getContent().stream().map(AbuseAdminController::toEntry).toList(),
                rows.getTotalElements(),
                rows.getNumber(),
                rows.getTotalPages());
    }

    @PostMapping("/{id}/uphold")
    public AbuseReportEntry uphold(@PathVariable UUID id) {
        return toEntry(service.uphold(id));
    }

    @PostMapping("/{id}/dismiss")
    public AbuseReportEntry dismiss(@PathVariable UUID id) {
        return toEntry(service.dismiss(id));
    }

    @PostMapping("/{id}/ownership-proven")
    public AbuseReportEntry ownershipProven(@PathVariable UUID id) {
        return toEntry(service.markOwnershipProven(id));
    }

    private static AbuseReportEntry toEntry(AbuseReport r) {
        return new AbuseReportEntry(
                r.getId(),
                r.getWebsite().getId(),
                r.getWebsite().getUrl(),
                r.getWebsite().getStatus().name(),
                r.getReporterEmail(),
                r.getMessage(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getProofDeadlineAt(),
                r.getResolvedAt());
    }

    public record AbuseReportEntry(
            UUID id,
            UUID websiteId,
            String websiteUrl,
            String websiteStatus,
            String reporterEmail,
            String message,
            AbuseReport.Status status,
            Instant createdAt,
            Instant proofDeadlineAt,
            Instant resolvedAt
    ) {}

    public record AbuseReportPageResponse(
            List<AbuseReportEntry> items,
            long totalElements,
            int page,
            int totalPages
    ) {}
}
