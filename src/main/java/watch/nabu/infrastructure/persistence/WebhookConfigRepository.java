package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.WebhookConfig;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    List<WebhookConfig> findByOrganizationId(UUID organizationId);

    List<WebhookConfig> findByOrganizationIdIn(Collection<UUID> organizationIds);

    List<WebhookConfig> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    Optional<WebhookConfig> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

    @Modifying
    @Query(value = "DELETE FROM webhook_configs WHERE organization_id IN (:orgIds)", nativeQuery = true)
    int deleteByOrganizationIdIn(@Param("orgIds") Collection<UUID> orgIds);
}
