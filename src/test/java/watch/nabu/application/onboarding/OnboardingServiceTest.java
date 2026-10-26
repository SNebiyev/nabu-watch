package watch.nabu.application.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    OrganizationRepository organizationRepository;
    @Mock
    OrganizationMemberRepository orgMemberRepository;
    @Mock
    AdminSignupNotifier adminSignupNotifier;
    @Mock
    AdminBootstrapService adminBootstrapService;

    @InjectMocks
    OnboardingService service;

    // ── processOAuthLogin: existing user by provider ──────────────────────────

    @Test
    @DisplayName("processOAuthLogin: returns existing user found by provider identity")
    void processOAuthLogin_existingByProvider_returnsUser() {
        User existing = User.builder().email("user@example.com").fullName("User")
                .oauthProvider("google").oauthProviderId("123")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(userRepository.findByOauthProviderAndOauthProviderId("google", "123"))
                .thenReturn(Optional.of(existing));

        User result = service.processOAuthLogin("user@example.com", "User", "google", "123");

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).save(any());
    }

    // ── processOAuthLogin: noreply email migration ────────────────────────────

    @Test
    @DisplayName("processOAuthLogin: migrates noreply email to real email")
    void processOAuthLogin_noreplyEmail_migratesEmail() {
        User existing = User.builder().email("123+user@users.noreply.github.com").fullName("User")
                .oauthProvider("github").oauthProviderId("456")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(userRepository.findByOauthProviderAndOauthProviderId("github", "456"))
                .thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("real@example.com")).thenReturn(Optional.empty());

        User result = service.processOAuthLogin("real@example.com", "User", "github", "456");

        assertThat(result.getEmail()).isEqualTo("real@example.com");
        verify(userRepository).save(existing);
    }

    @Test
    @DisplayName("processOAuthLogin: does not migrate noreply when target email taken")
    void processOAuthLogin_noreplyEmail_targetTaken_noMigration() {
        User existing = User.builder().email("123+user@users.noreply.github.com").fullName("User")
                .oauthProvider("github").oauthProviderId("456")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(userRepository.findByOauthProviderAndOauthProviderId("github", "456"))
                .thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("real@example.com")).thenReturn(Optional.of(mock(User.class)));

        User result = service.processOAuthLogin("real@example.com", "User", "github", "456");

        assertThat(result.getEmail()).contains("noreply.github.com");
    }

    // ── processOAuthLogin: existing user by email ─────────────────────────────

    @Test
    @DisplayName("processOAuthLogin: links OAuth to existing account found by email")
    void processOAuthLogin_existingByEmail_linksOAuth() {
        User existing = User.builder().email("user@example.com").fullName("User")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(userRepository.findByOauthProviderAndOauthProviderId("google", "789"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        User result = service.processOAuthLogin("user@example.com", "User", "google", "789");

        assertThat(result.getOauthProvider()).isEqualTo("google");
        assertThat(result.getOauthProviderId()).isEqualTo("789");
        verify(userRepository).save(existing);
    }

    @Test
    @DisplayName("processOAuthLogin: does not overwrite existing OAuth provider")
    void processOAuthLogin_existingByEmail_withExistingOAuth_noOverwrite() {
        User existing = User.builder().email("user@example.com").fullName("User")
                .oauthProvider("github").oauthProviderId("other")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(userRepository.findByOauthProviderAndOauthProviderId("google", "789"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(existing));

        User result = service.processOAuthLogin("user@example.com", "User", "google", "789");

        assertThat(result.getOauthProvider()).isEqualTo("github");
        verify(userRepository, never()).save(any());
    }

    // ── processOAuthLogin: new user ───────────────────────────────────────────

    @Test
    @DisplayName("processOAuthLogin: creates new user and personal org")
    void processOAuthLogin_newUser_createsAll() {
        when(userRepository.findByOauthProviderAndOauthProviderId("google", "new"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        User savedUser = User.builder().email("new@example.com").fullName("New User")
                .oauthProvider("google").oauthProviderId("new")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(savedUser, "id", UUID.randomUUID());

        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        Organization org = Organization.builder().name("New User's Workspace").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        when(organizationRepository.save(any())).thenReturn(org);

        User result = service.processOAuthLogin("new@example.com", "New User", "google", "new");

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        verify(organizationRepository).save(any());
        verify(orgMemberRepository).save(any());
    }

    @Test
    @DisplayName("processOAuthLogin: new user with null fullName uses email as fallback")
    void processOAuthLogin_nullFullName_usesEmail() {
        when(userRepository.findByOauthProviderAndOauthProviderId("google", "new"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        User savedUser = User.builder().email("new@example.com").fullName("new@example.com")
                .oauthProvider("google").oauthProviderId("new")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(savedUser, "id", UUID.randomUUID());

        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });

        Organization org = Organization.builder().name("test").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        when(organizationRepository.save(any())).thenReturn(org);

        service.processOAuthLogin("new@example.com", null, "google", "new");

        verify(userRepository, atLeastOnce()).save(argThat(u ->
                u.getFullName().equals("new@example.com")));
    }

    // ── first-admin bootstrap hook ────────────────────────────────────────────

    @Test
    @DisplayName("processOAuthLogin: asks the bootstrap service to promote the resolved user")
    void processOAuthLogin_invokesAdminBootstrap() {
        User existing = User.builder().email("admin@example.com").fullName("Admin")
                .oauthProvider("google").oauthProviderId("42")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(userRepository.findByOauthProviderAndOauthProviderId("google", "42"))
                .thenReturn(Optional.of(existing));

        service.processOAuthLogin("admin@example.com", "Admin", "google", "42");

        verify(adminBootstrapService).promoteIfEligible(existing);
    }
}
