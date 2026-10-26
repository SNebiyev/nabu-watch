package watch.nabu.application.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock
    UserRepository userRepository;

    private AdminBootstrapService service(String email) {
        return new AdminBootstrapService(userRepository, email);
    }

    private User user(String email, User.AccountStatus status, User.Role role) {
        User u = User.builder().email(email).fullName("Test").status(status).role(role).build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    @Test
    @DisplayName("promotes an ACTIVE user whose email matches, ignoring case and whitespace")
    void promotesActiveMatchingUser() {
        User u = user("Admin@Example.com", User.AccountStatus.ACTIVE, User.Role.USER);

        boolean promoted = service("  admin@example.COM ").promoteIfEligible(u);

        assertThat(promoted).isTrue();
        assertThat(u.getRole()).isEqualTo(User.Role.ADMIN);
        verify(userRepository).save(u);
    }

    @Test
    @DisplayName("never promotes an unverified (PENDING_VERIFICATION) account")
    void doesNotPromoteUnverified() {
        User u = user("admin@example.com", User.AccountStatus.PENDING_VERIFICATION, User.Role.USER);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isFalse();

        assertThat(u.getRole()).isEqualTo(User.Role.USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("does not promote suspended accounts")
    void doesNotPromoteSuspended() {
        User u = user("admin@example.com", User.AccountStatus.SUSPENDED, User.Role.USER);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("does not promote a different email")
    void doesNotPromoteOtherEmail() {
        User u = user("someone@example.com", User.AccountStatus.ACTIVE, User.Role.USER);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isFalse();
        assertThat(u.getRole()).isEqualTo(User.Role.USER);
    }

    @Test
    @DisplayName("is a no-op when the bootstrap email is empty (default)")
    void disabledWhenEmpty() {
        User u = user("admin@example.com", User.AccountStatus.ACTIVE, User.Role.USER);
        AdminBootstrapService svc = service("");

        assertThat(svc.isEnabled()).isFalse();
        assertThat(svc.promoteIfEligible(u)).isFalse();
        svc.promoteAtStartup();

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an existing ADMIN is left alone (no save, no second log line)")
    void alreadyAdmin() {
        User u = user("admin@example.com", User.AccountStatus.ACTIVE, User.Role.ADMIN);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("startup pass promotes an already existing ACTIVE account")
    void startupPromotesExisting() {
        User u = user("admin@example.com", User.AccountStatus.ACTIVE, User.Role.USER);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(u));

        service("Admin@Example.com").promoteAtStartup();

        assertThat(u.getRole()).isEqualTo(User.Role.ADMIN);
        verify(userRepository).save(u);
    }

    @Test
    @DisplayName("startup pass does nothing when the account does not exist yet")
    void startupNoAccount() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());

        service("admin@example.com").promoteAtStartup();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("startup pass does not promote an unverified existing account")
    void startupUnverified() {
        User u = user("admin@example.com", User.AccountStatus.PENDING_VERIFICATION, User.Role.USER);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(u));

        service("admin@example.com").promoteAtStartup();

        assertThat(u.getRole()).isEqualTo(User.Role.USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("does not promote when an ADMIN already exists (demoted bootstrap account stays demoted)")
    void doesNotPromoteWhenAdminExists() {
        User u = user("admin@example.com", User.AccountStatus.ACTIVE, User.Role.USER);
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(true);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isFalse();

        assertThat(u.getRole()).isEqualTo(User.Role.USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("promotes when no ADMIN exists yet")
    void promotesWhenNoAdminExists() {
        User u = user("admin@example.com", User.AccountStatus.ACTIVE, User.Role.USER);
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(false);

        assertThat(service("admin@example.com").promoteIfEligible(u)).isTrue();
        verify(userRepository).save(u);
    }
}
