package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watch.nabu.application.reporting.ComplianceReportService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.application.website.WebsiteService;

import java.util.UUID;

/**
 * PDF compliance report endpoint — agencies use this to forward audit results
 * to their clients. Ownership is enforced via {@link WebsiteService#requireAccess}.
 */
@RestController
@RequestMapping("/api/v1/websites")
@RequiredArgsConstructor
public class ComplianceReportController {

    private final ComplianceReportService complianceReportService;
    private final WebsiteService websiteService;
    private final SecurityContextService securityContext;

    /**
     * GET /api/v1/websites/{websiteId}/compliance-report.pdf
     * <p>
     * Returns the website's current compliance snapshot as a PDF attachment.
     * Requires a valid Bearer JWT; caller's organization must own the website.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = "/{websiteId}/compliance-report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadReport(
            @PathVariable UUID websiteId,
            @AuthenticationPrincipal UserDetails principal) {

        UUID orgId = securityContext.resolveOrgId(principal.getUsername());
        var website = websiteService.requireAccess(websiteId, orgId);

        byte[] pdf = complianceReportService.generatePdf(website);

        String filename = "nabu-compliance-report-"
                + ComplianceReportService.domainOf(website.getUrl())
                + "-" + ComplianceReportService.todayIso()
                + ".pdf";

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(disposition);
        headers.setContentLength(pdf.length);

        return new ResponseEntity<>(pdf, headers, 200);
    }
}
