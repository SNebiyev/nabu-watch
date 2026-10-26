package watch.nabu.application.organization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.notification.OrgNotificationSettingsService;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.OrganizationMember.Role;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Manages organization lifecycle and member operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationService {

    private final OrganizationRepository orgRepository;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final OrgNotificationSettingsService orgNotificationSettingsService;

    /**
     * Returns all organizations the user belongs to.
     */
    public List<Organization> listForUser(UUID userId) {
        return memberRepository.findAllByUserIdWithOrganization(userId).stream()
                .map(OrganizationMember::getOrganization)
                .toList();
    }

    /**
     * Returns the organization by ID.
     *
     * @throws NoSuchElementException if not found
     */
    public Organization findById(UUID orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found: " + orgId));
    }

    /**
     * Creates a new organization with the given user as OWNER.
     */
    @Transactional
    public Organization create(String name, UUID ownerUserId) {
        Organization org = orgRepository.save(
                Organization.builder().name(name).build());

        User owner = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + ownerUserId));

        memberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(owner)
                .role(Role.OWNER)
                .build());

        orgNotificationSettingsService.initDefaults(org, owner, Role.OWNER);

        // Switch the user's active org to the newly created one
        owner.setCurrentOrganizationId(org.getId());
        userRepository.save(owner);

        log.info("[org] Created: orgId={} name={} owner={}", org.getId(), name, ownerUserId);
        return org;
    }

    /**
     * Adds a user as a MEMBER of the organization.
     *
     * @throws NoSuchElementException if user or org not found
     * @throws IllegalStateException  if user is already a member
     */
    @Transactional
    public void addMember(UUID orgId, String memberEmail) {
        User user = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new NoSuchElementException(
                        "No user found with email: " + memberEmail));

        if (memberRepository.existsByOrganizationIdAndUserId(orgId, user.getId())) {
            throw new IllegalStateException("User is already a member of this organization");
        }

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found: " + orgId));

        memberRepository.save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(Role.MEMBER)
                .build());

        orgNotificationSettingsService.initDefaults(org, user, Role.MEMBER);
        log.info("[org] Member added: orgId={} userId={} email={}", orgId, user.getId(), memberEmail);
    }

    /**
     * Removes a member from the organization.
     * Owners cannot be removed (change role first).
     */
    @Transactional
    public void removeMember(UUID orgId, UUID userId) {
        OrganizationMember member = memberRepository
                .findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Member not found: orgId=" + orgId + " userId=" + userId));

        if (member.getRole() == Role.OWNER) {
            long ownerCount = memberRepository.findAllByOrganizationId(orgId).stream()
                    .filter(m -> m.getRole() == Role.OWNER)
                    .count();
            if (ownerCount <= 1) {
                throw new IllegalStateException(
                        "Cannot remove the last owner. Transfer ownership first.");
            }
        }

        orgNotificationSettingsService.deleteForMember(orgId, userId);
        memberRepository.delete(member);
        log.info("[org] Member removed: orgId={} userId={}", orgId, userId);
    }

    /**
     * Changes a member's role within the organization.
     */
    @Transactional
    public void changeMemberRole(UUID orgId, UUID userId, Role newRole) {
        OrganizationMember member = memberRepository
                .findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Member not found: orgId=" + orgId + " userId=" + userId));

        // Prevent removing the last OWNER by downgrading them
        if (member.getRole() == Role.OWNER && newRole == Role.MEMBER) {
            long ownerCount = memberRepository.findAllByOrganizationId(orgId).stream()
                    .filter(m -> m.getRole() == Role.OWNER)
                    .count();
            if (ownerCount <= 1) {
                throw new IllegalStateException(
                        "Cannot downgrade the last owner. Assign another owner first.");
            }
        }

        member.setRole(newRole);
        memberRepository.save(member);
        log.info("[org] Role changed: orgId={} userId={} newRole={}", orgId, userId, newRole);
    }

    /**
     * Lists all members of the organization with user details.
     */
    public List<OrganizationMember> listMembers(UUID orgId) {
        return memberRepository.findAllByOrganizationIdWithUser(orgId);
    }

    /**
     * Updates the organization name.
     */
    @Transactional
    public void updateName(UUID orgId, String newName) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new NoSuchElementException("Organization not found: " + orgId));
        org.setName(newName);
        orgRepository.save(org);
        log.info("[org] Renamed: orgId={} newName={}", orgId, newName);
    }

    /**
     * Verifies the user is a member of the given org.
     *
     * @throws org.springframework.security.access.AccessDeniedException if not a member
     */
    public void requireMembership(UUID orgId, UUID userId) {
        if (!memberRepository.existsByOrganizationIdAndUserId(orgId, userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Not a member of organization " + orgId);
        }
    }

    /**
     * Verifies the caller is an OWNER of the given org.
     *
     * @throws IllegalStateException if not an owner
     */
    public void requireOwner(UUID orgId, UUID userId) {
        if (!memberRepository.existsByOrganizationIdAndUserIdAndRole(orgId, userId, Role.OWNER)) {
            throw new IllegalStateException("User is not an owner of organization " + orgId);
        }
    }
}
