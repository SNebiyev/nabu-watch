package watch.nabu.application.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.MfaInvalidException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.web.dto.MfaSetupResponse;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaSetupServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    TotpService totpService;

    @InjectMocks
    MfaSetupService mfaSetupService;

    private User buildUser(String email) {
        User user = User.builder()
                .fullName("Alice")
                .email(email)
                .passwordHash("hash")
                .status(AccountStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setup generates secret, persists (unactivated), and returns response")
    void setup_generatesSecretAndReturnsResponse() {
        User user = buildUser("alice@example.com");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(totpService.generateSecret()).thenReturn("BASE32SECRET");
        when(totpService.getQrUri("alice@example.com", "BASE32SECRET"))
                .thenReturn("otpauth://totp/...");
        when(totpService.generateQrPngBase64("otpauth://totp/...")).thenReturn("base64png");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        MfaSetupResponse response = mfaSetupService.setup("alice@example.com");

        assertThat(response.secret()).isEqualTo("BASE32SECRET");
        assertThat(response.qrUri()).isEqualTo("otpauth://totp/...");
        assertThat(response.qrImageBase64()).isEqualTo("base64png");
        assertThat(user.getTotpSecret()).isEqualTo("BASE32SECRET");
        assertThat(user.isTotpEnabled()).isFalse(); // not active until confirmed
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("setup with unknown email → UserNotFoundException")
    void setup_unknownEmail_throwsUserNotFoundException() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mfaSetupService.setup("ghost@example.com"))
                .isInstanceOf(UserNotFoundException.class);

        verify(totpService, never()).generateSecret();
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm with valid TOTP code → totpEnabled set to true")
    void confirm_validCode_enablesTotp() {
        User user = buildUser("alice@example.com");
        user.setTotpSecret("SECRET");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(totpService.isCodeValid("SECRET", "123456")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        mfaSetupService.confirm("alice@example.com", "123456");

        assertThat(user.isTotpEnabled()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("confirm with invalid TOTP code → MfaInvalidException")
    void confirm_invalidCode_throwsMfaInvalidException() {
        User user = buildUser("alice@example.com");
        user.setTotpSecret("SECRET");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(totpService.isCodeValid("SECRET", "000000")).thenReturn(false);

        assertThatThrownBy(() -> mfaSetupService.confirm("alice@example.com", "000000"))
                .isInstanceOf(MfaInvalidException.class);

        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    @DisplayName("confirm with null totpSecret → MfaInvalidException")
    void confirm_nullSecret_throwsMfaInvalidException() {
        User user = buildUser("alice@example.com");
        user.setTotpSecret(null);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> mfaSetupService.confirm("alice@example.com", "123456"))
                .isInstanceOf(MfaInvalidException.class);
    }

    @Test
    @DisplayName("confirm with unknown email → UserNotFoundException")
    void confirm_unknownEmail_throwsUserNotFoundException() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mfaSetupService.confirm("ghost@example.com", "123456"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
