package watch.nabu.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watch.nabu.application.abuse.AbuseReportService;
import watch.nabu.domain.model.AbuseReport;

import java.time.Instant;
import java.util.UUID;

/**
 * Public abuse-report intake. Permitted without authentication so a domain
 * owner can complain even if they have no account. Listed in
 * {@code SecurityConfig} as {@code permitAll}.
 */
@RestController
@RequestMapping("/api/v1/abuse")
@RequiredArgsConstructor
public class AbuseReportController {

    private final AbuseReportService service;

    @PostMapping("/reports")
    public ResponseEntity<AbuseReportResponse> submit(
            @Valid @RequestBody AbuseReportRequest body,
            HttpServletRequest httpRequest) {
        AbuseReport report = service.submit(
                body.websiteId(), body.reporterEmail(), body.message(), httpRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AbuseReportResponse(
                        report.getId(),
                        report.getStatus(),
                        report.getProofDeadlineAt()));
    }

    public record AbuseReportRequest(
            @jakarta.validation.constraints.NotNull UUID websiteId,
            @NotBlank @Email @Size(max = 255) String reporterEmail,
            @NotBlank @Size(min = 20, max = 5000) String message
    ) {}

    public record AbuseReportResponse(
            UUID id,
            AbuseReport.Status status,
            Instant proofDeadlineAt
    ) {}
}
