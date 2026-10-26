package watch.nabu.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.WebsiteVerification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebsiteVerificationRepository
        extends JpaRepository<WebsiteVerification, UUID> {

    Optional<WebsiteVerification> findByWebsiteId(UUID websiteId);

    /**
     * Scheduler target: PENDING rows not yet expired, ordered by
     * {@code last_checked_at} ASC (nulls first — brand-new rows preempt
     * previously-polled ones).  Caller passes a {@link Pageable} such as
     * {@code PageRequest.of(0, 100)} to cap the batch.
     */
    @Query("""
            SELECT v FROM WebsiteVerification v
            WHERE v.status = watch.nabu.domain.model.WebsiteVerification.Status.PENDING
              AND v.expiresAt > :now
            ORDER BY v.lastCheckedAt ASC NULLS FIRST
            """)
    List<WebsiteVerification> findPendingBatch(@Param("now") Instant now, Pageable pageable);

    /**
     * PENDING rows that have passed their {@code expires_at} timestamp — the
     * scheduler transitions these to EXPIRED in a second pass.
     */
    @Query("""
            SELECT v FROM WebsiteVerification v
            WHERE v.status = watch.nabu.domain.model.WebsiteVerification.Status.PENDING
              AND v.expiresAt <= :now
            """)
    List<WebsiteVerification> findExpired(@Param("now") Instant now);

    /**
     * PENDING rows with &lt;= 24h remaining that have not yet had a warning
     * email dispatched. The {@code expiry_warning_sent_at} NULL check
     * guarantees one warning per challenge window even if the scheduler
     * ticks multiple times inside the warning band.
     */
    @Query("""
            SELECT v FROM WebsiteVerification v
            JOIN FETCH v.website w
            LEFT JOIN FETCH w.addedBy
            WHERE v.status = watch.nabu.domain.model.WebsiteVerification.Status.PENDING
              AND v.expiresAt > :now
              AND v.expiresAt <= :warnBefore
              AND v.expiryWarningSentAt IS NULL
            """)
    List<WebsiteVerification> findExpiringWithoutWarning(
            @Param("now") Instant now, @Param("warnBefore") Instant warnBefore);
}
