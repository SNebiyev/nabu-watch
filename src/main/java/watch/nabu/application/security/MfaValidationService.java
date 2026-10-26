package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.MfaChallengeRequiredException;
import watch.nabu.application.exception.MfaInvalidException;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

/**
 * Validates Admin TOTP step-up for force-scan bypass.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only {@link User.Role#ADMIN} users may use {@code ?force=true}.</li>
 *   <li>The Admin must have completed MFA setup ({@code totpEnabled=true}).</li>
 *   <li>The supplied {@code mfaToken} must be a currently valid 6-digit TOTP code.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MfaValidationService {

    private final UserRepository userRepository;
    private final TotpService totpService;

    /**
     * Validates that the principal is an Admin with TOTP enabled and that the
     * provided code is currently valid.
     *
     * @param principalEmail the authenticated user's email (from JWT)
     * @param mfaToken       the 6-digit TOTP code from the {@code X-MFA-Token} header
     * @throws MfaChallengeRequiredException if the user is not an Admin or has not set up TOTP
     * @throws MfaInvalidException           if the TOTP code is absent or incorrect
     */
    @Transactional(readOnly = true)
    public void validateForce(String principalEmail, String mfaToken) {
        User user = userRepository.findByEmail(principalEmail)
                .orElseThrow(() -> new UserNotFoundException(principalEmail));

        if (user.getRole() != User.Role.ADMIN) {
            throw new MfaChallengeRequiredException();
        }
        if (!user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new MfaChallengeRequiredException();
        }
        if (mfaToken == null || !totpService.isCodeValid(user.getTotpSecret(), mfaToken)) {
            throw new MfaInvalidException();
        }
    }
}
