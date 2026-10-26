package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.OrgNotificationSetting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgNotificationSettingRepository extends JpaRepository<OrgNotificationSetting, UUID> {

    Optional<OrgNotificationSetting> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    @Query("SELECT s.user.id FROM OrgNotificationSetting s WHERE s.organization.id = :orgId AND s.alertsEnabled = true")
    List<UUID> findAlertEnabledUserIdsByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT s.organization.id FROM OrgNotificationSetting s WHERE s.user.id = :userId AND s.digestEnabled = true")
    List<UUID> findDigestEnabledOrgIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT s FROM OrgNotificationSetting s JOIN FETCH s.user WHERE s.organization.id = :orgId")
    List<OrgNotificationSetting> findAllByOrganizationIdWithUser(@Param("orgId") UUID orgId);

    void deleteByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    void deleteByUserId(UUID userId);
}
