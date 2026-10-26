package watch.nabu.application.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.MfaChallengeRequiredException;
import watch.nabu.application.exception.MfaInvalidException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.domain.model.User.Role;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaValidationServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    TotpService totpService;

    @InjectMocks
    MfaValidationService mfaValidationService;

    private User buildAdmin(boolean totpEnabled, String totpSecret) {
        User user = User.builder()
                .fullName("Admin")
                .email("admin@example.com")
                .passwordHash("hash")
                .role(Role.ADMIN)
                .status(AccountStatus.ACTIVE)
                .totpEnabled(totpEnabled)
                .totpSecret(totpSecret)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private User buildRegularUser() {
        User user = User.builder()
                .fullName("User")
                .email("user@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .status(AccountStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    // ── validateForce happy path ───────────────────────────────────────────────

    @Test
    @DisplayName("validateForce for admin with TOTP enabled and valid code → no exception")
    void validateForce_adminValidCode_passes() {
        User admin = buildAdmin(true, "SECRET");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(totpService.isCodeValid("SECRET", "123456")).thenReturn(true);

        assertThatCode(() ->
                mfaValidationService.validateForce("admin@example.com", "123456")
        ).doesNotThrowAnyException();
    }

    // ── Non-admin user ────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateForce for non-admin → MfaChallengeRequiredException")
    void validateForce_nonAdmin_throwsMfaChallengeRequired() {
        User regularUser = buildRegularUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(regularUser));

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("user@example.com", "123456")
        ).isInstanceOf(MfaChallengeRequiredException.class);

        verifyNoInteractions(totpService);
    }

    // ── TOTP not set up ───────────────────────────────────────────────────────

    @Test
    @DisplayName("validateForce for admin without TOTP enabled → MfaChallengeRequiredException")
    void validateForce_adminTotpNotEnabled_throwsMfaChallengeRequired() {
        User admin = buildAdmin(false, null);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("admin@example.com", "123456")
        ).isInstanceOf(MfaChallengeRequiredException.class);

        verifyNoInteractions(totpService);
    }

    @Test
    @DisplayName("validateForce for admin with totpEnabled=true but null secret → MfaChallengeRequiredException")
    void validateForce_adminNullSecret_throwsMfaChallengeRequired() {
        User admin = buildAdmin(true, null);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("admin@example.com", "123456")
        ).isInstanceOf(MfaChallengeRequiredException.class);
    }

    // ── Invalid TOTP code ─────────────────────────────────────────────────────

    @Test
    @DisplayName("validateForce with wrong TOTP code → MfaInvalidException")
    void validateForce_wrongCode_throwsMfaInvalidException() {
        User admin = buildAdmin(true, "SECRET");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(totpService.isCodeValid("SECRET", "000000")).thenReturn(false);

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("admin@example.com", "000000")
        ).isInstanceOf(MfaInvalidException.class);
    }

    @Test
    @DisplayName("validateForce with null mfaToken → MfaInvalidException")
    void validateForce_nullToken_throwsMfaInvalidException() {
        User admin = buildAdmin(true, "SECRET");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("admin@example.com", null)
        ).isInstanceOf(MfaInvalidException.class);

        verifyNoInteractions(totpService);
    }

    // ── User not found ────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateForce with unknown email → UserNotFoundException")
    void validateForce_unknownEmail_throwsUserNotFoundException() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                mfaValidationService.validateForce("ghost@example.com", "123456")
        ).isInstanceOf(UserNotFoundException.class);
    }
}
