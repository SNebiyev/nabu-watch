package watch.nabu.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watch.nabu.domain.model.OrganizationMember;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    List<OrganizationMember> findAllByUserId(UUID userId);

    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.organization WHERE m.user.id = :userId")
    List<OrganizationMember> findAllByUserIdWithOrganization(@Param("userId") UUID userId);

    List<OrganizationMember> findAllByOrganizationId(UUID organizationId);

    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.user WHERE m.organization.id = :orgId")
    List<OrganizationMember> findAllByOrganizationIdWithUser(@Param("orgId") UUID orgId);

    @Query("SELECT m FROM OrganizationMember m JOIN FETCH m.user u WHERE m.organization.id = :orgId AND u.status = 'ACTIVE'")
    List<OrganizationMember> findActiveByOrganizationIdWithUser(@Param("orgId") UUID orgId);

    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserIdAndRole(
            UUID organizationId, UUID userId, OrganizationMember.Role role);

    long countByOrganizationId(UUID organizationId);

    void deleteByUserId(UUID userId);

    @Query("SELECT m.user FROM OrganizationMember m WHERE m.organization.id = :orgId AND m.role = watch.nabu.domain.model.OrganizationMember.Role.OWNER")
    List<watch.nabu.domain.model.User> findOwnersOfOrganization(@Param("orgId") UUID orgId);

    /** One-shot lookup of all org ids where at least one OWNER is VERIFIED. */
    @Query("SELECT DISTINCT m.organization.id FROM OrganizationMember m " +
            "WHERE m.role = watch.nabu.domain.model.OrganizationMember.Role.OWNER " +
            "AND m.user.verificationStatus = watch.nabu.domain.model.User.VerificationStatus.VERIFIED")
    Set<UUID> findOrgIdsWithVerifiedOwner();
}
