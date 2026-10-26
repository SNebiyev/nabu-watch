package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.application.website.WebsiteService;
import watch.nabu.domain.model.AbuseReport;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.AbuseReportRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owner-facing read-only view of abuse reports filed against one of the
 * caller's websites. Surfaced in the site expand panel so owners understand
 * why their site was paused / removed without having to contact support.
 *
 * <p>The {@code reporter_email} and free-form {@code message} are
 * deliberately NOT returned here — they belong to the admin console and
 * could enable harassment if shown to the reported site owner. The owner
 * sees only the lifecycle state + timestamps + proof deadline.
 */
@RestController
@RequestMapping("/api/v1/websites/{websiteId}/abuse-reports")
@RequiredArgsConstructor
public class OwnerAbuseReportController {

    private final SecurityContextService securityContext;
    private final WebsiteService websiteService;
    private final AbuseReportRepository reportRepository;

    @GetMapping
    public List<OwnerReportEntry> list(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID websiteId) {
        User user = securityContext.resolveUser(principal.getUsername());
        websiteService.requireAccess(websiteId, user.getCurrentOrganizationId());
        return reportRepository.findAllByWebsiteIdOrderByCreatedAtDesc(websiteId).stream()
                .map(OwnerReportEntry::from)
                .toList();
    }

    public record OwnerReportEntry(
            UUID id,
            AbuseReport.Status status,
            Instant createdAt,
            Instant proofDeadlineAt,
            Instant resolvedAt
    ) {
        static OwnerReportEntry from(AbuseReport r) {
            return new OwnerReportEntry(
                    r.getId(), r.getStatus(),
                    r.getCreatedAt(), r.getProofDeadlineAt(), r.getResolvedAt());
        }
    }
}
