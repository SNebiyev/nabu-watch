package watch.nabu.application.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watch.nabu.domain.model.WebsiteVerification;
import watch.nabu.domain.model.WebsiteVerification.Status;
import watch.nabu.infrastructure.persistence.WebsiteVerificationRepository;

import java.time.Instant;
import java.util.List;

/**
 * Background poller for PENDING {@link WebsiteVerification} rows.
 *
 * <p>Runs every 6 hours (matches the plan's §2.2 cadence).  Each tick:
 * <ol>
 *   <li>Transitions rows whose {@code expires_at} has passed to {@code EXPIRED}.</li>
 *   <li>Runs a DNS TXT lookup for up to 100 non-expired PENDING rows, oldest-
 *       checked first, so a quiet system doesn't monopolise scheduler time on
 *       the same rows.</li>
 * </ol>
 *
 * <p>Manual "Check now" requests bypass this scheduler entirely — the
 * endpoint calls {@link DomainVerificationService#check} directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainVerificationScheduler {

    private static final int BATCH_SIZE = 100;

    private final WebsiteVerificationRepository verificationRepository;
    private final DomainVerificationService verificationService;

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    public void runTick() {
        int expired = verificationService.sweepExpired();
        int warned = verificationService.sweepExpiryWarnings();

        List<WebsiteVerification> batch = verificationRepository.findPendingBatch(
                Instant.now(), PageRequest.of(0, BATCH_SIZE));
        int verified = 0;
        for (WebsiteVerification v : batch) {
            try {
                if (verificationService.check(v.getWebsite().getId()).getStatus() == Status.VERIFIED) {
                    verified++;
                }
            } catch (Exception e) {
                // One bad row must not abort the batch — log and continue.
                log.warn("[verify] check failed for websiteId={}", v.getWebsite().getId(), e);
            }
        }
        log.info("[verify-scheduler] tick: expired={} warned={} polled={} verified={}",
                expired, warned, batch.size(), verified);
    }
}
