package watch.nabu.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.AbuseReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AbuseReportRepository extends JpaRepository<AbuseReport, UUID> {
    long countByWebsiteIdAndStatus(UUID websiteId, AbuseReport.Status status);

    /**
     * True when the website has at least one abuse report still in a non-terminal
     * status (PENDING or UPHELD). Used by {@code WebsiteService.delete} to block
     * owner self-delete so the {@code ON DELETE CASCADE} on abuse_reports can't
     * be used to destroy active triage state.
     */
    @Query("""
            SELECT (COUNT(r) > 0) FROM AbuseReport r
            WHERE r.website.id = :websiteId
              AND r.status IN (
                watch.nabu.domain.model.AbuseReport.Status.PENDING,
                watch.nabu.domain.model.AbuseReport.Status.UPHELD)
            """)
    boolean existsOpenByWebsiteId(@Param("websiteId") UUID websiteId);

    @Query("""
            SELECT r FROM AbuseReport r
            JOIN FETCH r.website w
            JOIN FETCH w.organization
            LEFT JOIN FETCH w.addedBy
            WHERE (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC
            """)
    Page<AbuseReport> findAdminPage(AbuseReport.Status status, Pageable pageable);

    @Query("""
            SELECT r FROM AbuseReport r
            JOIN FETCH r.website w
            JOIN FETCH w.organization
            LEFT JOIN FETCH w.addedBy
            WHERE r.id = :id
            """)
    Optional<AbuseReport> findByIdWithWebsite(UUID id);

    @Query("""
            SELECT r FROM AbuseReport r
            JOIN FETCH r.website w
            LEFT JOIN FETCH w.addedBy
            WHERE r.status = :status AND r.proofDeadlineAt < :cutoff
            """)
    List<AbuseReport> findAllByStatusAndProofDeadlineAtBefore(
            AbuseReport.Status status, Instant cutoff);

    List<AbuseReport> findAllByWebsiteIdOrderByCreatedAtDesc(UUID websiteId);
}
