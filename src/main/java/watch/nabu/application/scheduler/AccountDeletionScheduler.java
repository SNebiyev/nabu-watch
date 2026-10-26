package watch.nabu.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watch.nabu.application.gdpr.AccountDeletionService;

/**
 * Nightly job that hard-deletes user accounts whose 30-day grace period
 * has expired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionScheduler {

    private final AccountDeletionService accountDeletionService;

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void purgeExpiredDeletions() {
        int count = accountDeletionService.purgeExpiredAccounts();
        log.info("[gdpr] Purged {} expired accounts", count);
    }
}
