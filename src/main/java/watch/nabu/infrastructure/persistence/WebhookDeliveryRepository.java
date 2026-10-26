package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.WebhookDelivery;
import watch.nabu.domain.model.WebhookDelivery.DeliveryStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(UUID webhookConfigId);

    @Query("""
            SELECT d FROM WebhookDelivery d
            WHERE d.status = :status
              AND d.attemptCount < :maxAttempts
              AND d.nextRetryAt <= :now
            ORDER BY d.nextRetryAt ASC
            """)
    List<WebhookDelivery> findPendingRetries(
            @Param("status") DeliveryStatus status,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") Instant now
    );

    @Modifying
    @Query(value = "DELETE FROM webhook_deliveries WHERE webhook_config_id IN (:configIds)", nativeQuery = true)
    int deleteByWebhookConfigIdIn(@Param("configIds") Collection<UUID> configIds);
}
