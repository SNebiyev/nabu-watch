package watch.nabu.application.organization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    OrganizationRepository orgRepository;
    @Mock
    OrganizationMemberRepository memberRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    OrgNotificationSettingsService orgNotificationSettingsService;

    @InjectMocks
    OrganizationService service;

    private User testUser;
    private Organization testOrg;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        testUser = User.builder().email("owner@test.com").fullName("Owner").status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(testUser, "id", UUID.randomUUID());

        testOrg = Organization.builder().name("Test Org").build();
        ReflectionTestUtils.setField(testOrg, "id", orgId);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: creates org, adds owner, switches user org")
    void create_happyPath() {
        when(orgRepository.save(any())).thenReturn(testOrg);
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));

        Organization result = service.create("Test Org", testUser.getId());

        assertThat(result.getName()).isEqualTo("Test Org");
        verify(memberRepository).save(argThat(m -> m.getRole() == Role.OWNER));
        assertThat(testUser.getCurrentOrganizationId()).isEqualTo(orgId);
        verify(userRepository).save(testUser);
    }

    @Test
    @DisplayName("create: user not found throws NoSuchElementException")
    void create_userNotFound_throws() {
        when(orgRepository.save(any())).thenReturn(testOrg);
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("Org", UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── addMember ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addMember: adds user as MEMBER to org")
    void addMember_happyPath() {
        when(userRepository.findByEmail("member@test.com")).thenReturn(Optional.of(testUser));
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, testUser.getId())).thenReturn(false);
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(testOrg));

        service.addMember(orgId, "member@test.com");

        verify(memberRepository).save(argThat(m -> m.getRole() == Role.MEMBER));
    }

    @Test
    @DisplayName("addMember: throws when user not found by email")
    void addMember_userNotFound_throws() {
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(orgId, "unknown@test.com"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("addMember: throws when already a member")
    void addMember_alreadyMember_throws() {
        when(userRepository.findByEmail("member@test.com")).thenReturn(Optional.of(testUser));
        when(memberRepository.existsByOrganizationIdAndUserId(orgId, testUser.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(orgId, "member@test.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeMember: removes non-owner member")
    void removeMember_nonOwner_removes() {
        OrganizationMember member = OrganizationMember.builder()
                .organization(testOrg).user(testUser).role(Role.MEMBER).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, testUser.getId()))
                .thenReturn(Optional.of(member));

        service.removeMember(orgId, testUser.getId());

        verify(memberRepository).delete(member);
    }

    @Test
    @DisplayName("removeMember: prevents removing last owner")
    void removeMember_lastOwner_throws() {
        OrganizationMember owner = OrganizationMember.builder()
                .organization(testOrg).user(testUser).role(Role.OWNER).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, testUser.getId()))
                .thenReturn(Optional.of(owner));
        when(memberRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(owner));

        assertThatThrownBy(() -> service.removeMember(orgId, testUser.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    @DisplayName("removeMember: allows removing owner when another owner exists")
    void removeMember_ownerWithOtherOwner_removes() {
        User otherUser = User.builder().email("other@test.com").fullName("Other").status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(otherUser, "id", UUID.randomUUID());

        OrganizationMember owner1 = OrganizationMember.builder()
                .organization(testOrg).user(testUser).role(Role.OWNER).build();
        OrganizationMember owner2 = OrganizationMember.builder()
                .organization(testOrg).user(otherUser).role(Role.OWNER).build();

        when(memberRepository.findByOrganizationIdAndUserId(orgId, testUser.getId()))
                .thenReturn(Optional.of(owner1));
        when(memberRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(owner1, owner2));

        service.removeMember(orgId, testUser.getId());

        verify(memberRepository).delete(owner1);
    }

    @Test
    @DisplayName("removeMember: member not found throws")
    void removeMember_notFound_throws() {
        when(memberRepository.findByOrganizationIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeMember(orgId, UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── changeMemberRole ──────────────────────────────────────────────────────

    @Test
    @DisplayName("changeMemberRole: promotes member to owner")
    void changeMemberRole_promoteToOwner() {
        OrganizationMember member = OrganizationMember.builder()
                .organization(testOrg).user(testUser).role(Role.MEMBER).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, testUser.getId()))
                .thenReturn(Optional.of(member));

        service.changeMemberRole(orgId, testUser.getId(), Role.OWNER);

        assertThat(member.getRole()).isEqualTo(Role.OWNER);
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("changeMemberRole: prevents downgrading last owner")
    void changeMemberRole_downgradeLastOwner_throws() {
        OrganizationMember owner = OrganizationMember.builder()
                .organization(testOrg).user(testUser).role(Role.OWNER).build();
        when(memberRepository.findByOrganizationIdAndUserId(orgId, testUser.getId()))
                .thenReturn(Optional.of(owner));
        when(memberRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(owner));

        assertThatThrownBy(() -> service.changeMemberRole(orgId, testUser.getId(), Role.MEMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    @DisplayName("changeMemberRole: member not found throws")
    void changeMemberRole_notFound_throws() {
        when(memberRepository.findByOrganizationIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeMemberRole(orgId, UUID.randomUUID(), Role.OWNER))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── listMembers ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listMembers: delegates to repository")
    void listMembers_delegates() {
        when(memberRepository.findAllByOrganizationIdWithUser(orgId)).thenReturn(List.of());

        List<OrganizationMember> result = service.listMembers(orgId);

        assertThat(result).isEmpty();
        verify(memberRepository).findAllByOrganizationIdWithUser(orgId);
    }

    // ── updateName ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateName: updates org name")
    void updateName_updatesName() {
        when(orgRepository.findById(orgId)).thenReturn(Optional.of(testOrg));

        service.updateName(orgId, "New Name");

        assertThat(testOrg.getName()).isEqualTo("New Name");
        verify(orgRepository).save(testOrg);
    }

    @Test
    @DisplayName("updateName: org not found throws")
    void updateName_orgNotFound_throws() {
        when(orgRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateName(UUID.randomUUID(), "New"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── requireOwner ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("requireOwner: passes when user is owner")
    void requireOwner_isOwner_passes() {
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(orgId, testUser.getId(), Role.OWNER))
                .thenReturn(true);

        service.requireOwner(orgId, testUser.getId()); // no exception
    }

    @Test
    @DisplayName("requireOwner: throws when user is not owner")
    void requireOwner_notOwner_throws() {
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(any(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requireOwner(orgId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
