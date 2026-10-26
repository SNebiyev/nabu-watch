package watch.nabu.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.ai.SecurityRemediationService;
import watch.nabu.application.security.DnsHygieneInspector;
import watch.nabu.application.security.ExposedFilesInspector;
import watch.nabu.application.security.JsLibraryCveInspector;
import watch.nabu.application.security.OpenPortsInspector;
import watch.nabu.application.security.SecurityHeadersInspector;
import watch.nabu.application.security.SecurityScanAccessService;
import watch.nabu.application.security.TlsGradeInspector;
import watch.nabu.application.security.TyposquatWatchInspector;
import watch.nabu.domain.model.SecurityScan;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.util.List;
import java.util.UUID;

/**
 * Owner-triggered security scans. Every endpoint enforces the same
 * three-layer gate: authenticated org member → website belongs to that
 * org → verified domain AND the scan is shipped. The scheduler
 * never auto-dispatches these scans — see
 * {@code MonitorRepository#findOverdueMonitorIds}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityScanController {

    private final WebsiteRepository websiteRepository;
    private final SecurityScanAccessService accessService;
    private final SecurityHeadersInspector securityHeadersInspector;
    private final ExposedFilesInspector exposedFilesInspector;
    private final TlsGradeInspector tlsGradeInspector;
    private final DnsHygieneInspector dnsHygieneInspector;
    private final OpenPortsInspector openPortsInspector;
    private final JsLibraryCveInspector jsLibraryCveInspector;
    private final TyposquatWatchInspector typosquatWatchInspector;
    private final SecurityRemediationService remediationService;

    @GetMapping("/websites/{id}/features")
    public List<SecurityScanAccessService.ScanStatus> features(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeOwner(websiteId, request);
        return accessService.list(website);
    }

    @PostMapping("/websites/{id}/headers-scan")
    public SecurityHeadersInspector.Result headersScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.SECURITY_HEADERS, request);
        log.info("[security-headers] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return securityHeadersInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/exposed-files-scan")
    public ExposedFilesInspector.Result exposedFilesScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.EXPOSED_FILES, request);
        log.info("[exposed-files] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return exposedFilesInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/tls-grade-scan")
    public TlsGradeInspector.Result tlsGradeScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.TLS_GRADE, request);
        log.info("[tls-grade] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return tlsGradeInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/dns-hygiene-scan")
    public DnsHygieneInspector.Result dnsHygieneScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.DNS_HYGIENE, request);
        log.info("[dns-hygiene] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return dnsHygieneInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/open-ports-scan")
    public OpenPortsInspector.Result openPortsScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.OPEN_PORTS, request);
        log.info("[open-ports] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return openPortsInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/js-library-cve-scan")
    public JsLibraryCveInspector.Result jsLibraryCveScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.JS_LIBRARY_CVE, request);
        log.info("[js-library-cve] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return jsLibraryCveInspector.inspect(website.getUrl());
    }

    @PostMapping("/websites/{id}/typosquat-watch-scan")
    public TyposquatWatchInspector.Result typosquatWatchScan(
            @PathVariable("id") UUID websiteId,
            HttpServletRequest request) {
        Website website = authorizeScan(websiteId, SecurityScan.TYPOSQUAT_WATCH, request);
        log.info("[typosquat-watch] scan triggered orgId={} websiteId={}",
                website.getOrganization().getId(), websiteId);
        return typosquatWatchInspector.inspect(website.getUrl());
    }

    public record RemediationRequest(String scan, String findingKey, String findingContext) { }

    public record RemediationResponse(String remediation) { }

    @PostMapping("/websites/{id}/remediation")
    public RemediationResponse remediation(
            @PathVariable("id") UUID websiteId,
            @RequestBody RemediationRequest body,
            HttpServletRequest request) {
        Website website = authorizeOwner(websiteId, request);
        if (website.getVerifiedAt() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI remediation is available only on verified websites");
        }
        if (body == null || body.scan() == null || body.findingKey() == null || body.findingContext() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scan, findingKey and findingContext are required");
        }

        SecurityRemediationService.Request req = new SecurityRemediationService.Request(
                website.getOrganization().getId(),
                website.getUrl(),
                body.scan(),
                body.findingKey(),
                body.findingContext()
        );
        String remediation = remediationService.getRemediation(req);
        log.info("[ai-remediation] orgId={} websiteId={} scan={} findingKey={}",
                website.getOrganization().getId(), websiteId, body.scan(), body.findingKey());
        return new RemediationResponse(remediation);
    }

    // ── Authorisation helpers ─────────────────────────────────────────────────

    private Website authorizeOwner(UUID websiteId, HttpServletRequest request) {
        UUID orgId = (UUID) request.getAttribute("orgId");
        if (orgId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Website not found"));
        if (!website.getOrganization().getId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Website does not belong to your organisation");
        }
        return website;
    }

    private Website authorizeScan(UUID websiteId, SecurityScan scan, HttpServletRequest request) {
        Website website = authorizeOwner(websiteId, request);
        if (website.getVerifiedAt() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Security monitors are available only on verified websites");
        }
        if (!accessService.isAllowed(website, scan)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    scan.label() + " is not available yet");
        }
        return website;
    }
}
