package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.ComplianceRegion;

import java.util.Optional;
import java.util.UUID;

public interface ComplianceRegionRepository extends JpaRepository<ComplianceRegion, UUID> {

    Optional<ComplianceRegion> findByCode(String code);

    Optional<ComplianceRegion> findByGlobalDefaultTrue();

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ComplianceRegion cr SET cr.active = :active WHERE cr.id = :id")
    void updateActive(@Param("id") UUID id, @Param("active") boolean active);
}
