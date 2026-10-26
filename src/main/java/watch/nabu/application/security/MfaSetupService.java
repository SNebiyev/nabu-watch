package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.MfaInvalidException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.web.dto.MfaSetupResponse;

/**
 * Handles MFA enrollment: secret generation and TOTP confirmation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaSetupService {

    private final UserRepository userRepository;
    private final TotpService totpService;

    /**
     * Generates a new TOTP secret for the user and persists it (unactivated).
     * The returned secret and QR URI must be presented to the user once;
     * the user confirms by calling {@link #confirm(String, String)}.
     */
    @Transactional
    public MfaSetupResponse setup(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        String secret = totpService.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(false); // not active until confirmed
        userRepository.save(user);

        String qrUri = totpService.getQrUri(email, secret);
        String qrImageBase64 = totpService.generateQrPngBase64(qrUri);
        log.info("MFA setup initiated: userId={}", user.getId());
        return new MfaSetupResponse(secret, qrUri, qrImageBase64);
    }

    /**
     * Validates a live TOTP code against the user's pending secret and,
     * on success, sets {@code totpEnabled = true}.
     *
     * @throws MfaInvalidException if the code is incorrect or no secret is set
     */
    @Transactional
    public void confirm(String email, String code) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        if (user.getTotpSecret() == null
                || !totpService.isCodeValid(user.getTotpSecret(), code)) {
            throw new MfaInvalidException();
        }

        user.setTotpEnabled(true);
        userRepository.save(user);
        log.info("MFA confirmed and enabled: userId={}", user.getId());
    }
}
