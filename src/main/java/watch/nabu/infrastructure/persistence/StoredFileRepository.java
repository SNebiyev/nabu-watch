package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.StoredFile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    List<StoredFile> findByEntityTypeAndEntityId(StoredFile.EntityType entityType, UUID entityId);

    List<StoredFile> findByOrganizationId(UUID organizationId);

    List<StoredFile> findByOrganizationIdIn(Collection<UUID> organizationIds);

    List<StoredFile> findByTemporaryTrueAndExpiresAtBefore(Instant cutoff);

    @Modifying
    @Query("DELETE FROM StoredFile sf WHERE sf.organizationId IN :orgIds")
    int deleteByOrganizationIdIn(@Param("orgIds") Collection<UUID> orgIds);
}
