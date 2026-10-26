package watch.nabu.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the email-verification wall implemented in
 * {@link NabuUserDetailsService}.
 *
 * <p>Contract: {@code VERIFIED} authority is granted iff the user's account
 * status is {@code ACTIVE}.  PENDING / SUSPENDED / DELETED users receive only
 * their role authority, which means the security-config rules that guard
 * mutating endpoints ({@code /api/v1/websites/**}, etc.) reject them.
 */
@ExtendWith(MockitoExtension.class)
class NabuUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NabuUserDetailsService service;

    private static User baseUser(User.AccountStatus status, User.Role role) {
        return User.builder()
                .email("x@x.io")
                .fullName("X")
                .passwordHash("$2a$12$abc")
                .status(status)
                .role(role)
                .build();
    }

    @Test
    void activeUserReceivesVerifiedAuthority() {
        User user = baseUser(User.AccountStatus.ACTIVE, User.Role.USER);
        when(userRepository.findByEmail("active@x.io")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("active@x.io");

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", NabuUserDetailsService.VERIFIED_AUTHORITY);
    }

    @Test
    void pendingVerificationUserDoesNotReceiveVerifiedAuthority() {
        User user = baseUser(User.AccountStatus.PENDING_VERIFICATION, User.Role.USER);
        when(userRepository.findByEmail("pending@x.io")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("pending@x.io");

        // Still disabled — blocks password login via DaoAuthenticationProvider
        assertThat(details.isEnabled()).isFalse();
        // But when a JWT-carrying request arrives, the principal will have
        // ROLE_USER only — no VERIFIED, so SecurityConfig 403s protected routes.
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");
    }

    @Test
    void suspendedUserIsLockedAndUnverified() {
        User user = baseUser(User.AccountStatus.SUSPENDED, User.Role.USER);
        when(userRepository.findByEmail("suspended@x.io")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("suspended@x.io");

        assertThat(details.isAccountNonLocked()).isFalse();
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .doesNotContain(NabuUserDetailsService.VERIFIED_AUTHORITY);
    }

    @Test
    void deletedUserIsLockedAndUnverified() {
        User user = baseUser(User.AccountStatus.DELETED, User.Role.USER);
        when(userRepository.findByEmail("deleted@x.io")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("deleted@x.io");

        assertThat(details.isAccountNonLocked()).isFalse();
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .doesNotContain(NabuUserDetailsService.VERIFIED_AUTHORITY);
    }

    @Test
    void activeAdminReceivesBothRoleAndVerified() {
        User user = baseUser(User.AccountStatus.ACTIVE, User.Role.ADMIN);
        when(userRepository.findByEmail("admin@x.io")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("admin@x.io");

        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", NabuUserDetailsService.VERIFIED_AUTHORITY);
    }

    @Test
    void missingUserThrows() {
        when(userRepository.findByEmail("nobody@x.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@x.io"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void userIsVerifiedConvenienceMethodMirrorsStatus() {
        assertThat(baseUser(User.AccountStatus.ACTIVE, User.Role.USER).isVerified()).isTrue();
        assertThat(baseUser(User.AccountStatus.PENDING_VERIFICATION, User.Role.USER).isVerified()).isFalse();
        assertThat(baseUser(User.AccountStatus.SUSPENDED, User.Role.USER).isVerified()).isFalse();
        assertThat(baseUser(User.AccountStatus.DELETED, User.Role.USER).isVerified()).isFalse();
    }
}
