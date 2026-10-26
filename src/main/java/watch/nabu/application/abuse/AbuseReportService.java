package watch.nabu.application.abuse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.domain.model.AbuseReport;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.AbuseReportRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.web.HttpRequestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Intake for third-party complaints that a website is being scanned without
 * the owner's permission.
 *
 * <p>The endpoint is unauthenticated so a non-customer domain owner can
 * complain. Because the reporter's identity is unverified at submission, the
 * report is recorded as {@code PENDING} only — the website is <b>not</b>
 * auto-paused. Pausing or removing the site requires an admin to triage the
 * queue (and, in a follow-up, an email-verified ownership challenge); this
 * prevents anyone who knows a {@code websiteId} from disabling monitoring on
 * a competitor's site with a single anonymous POST.
 *
 * <p>The {@code proof_deadline_at} is set so a future scheduler can age out
 * unactioned reports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AbuseReportService {

    static final long PROOF_WINDOW_HOURS = 48;

    private final AbuseReportRepository reportRepository;
    private final WebsiteRepository websiteRepository;

    public AbuseReport submit(UUID websiteId, String reporterEmail, String message,
                              HttpServletRequest httpRequest) {
        Website website = websiteRepository.findById(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        Instant deadline = Instant.now().plus(Duration.ofHours(PROOF_WINDOW_HOURS));
        AbuseReport report = reportRepository.save(AbuseReport.builder()
                .website(website)
                .reporterEmail(reporterEmail)
                .reporterIp(HttpRequestUtils.clientIp(httpRequest))
                .message(message)
                .status(AbuseReport.Status.PENDING)
                .proofDeadlineAt(deadline)
                .build());

        log.warn("[abuse] Report queued for admin review: websiteId={} url={} reporterEmail={} deadlineAt={}",
                websiteId, website.getUrl(), reporterEmail, deadline);
        return report;
    }
}
