package watch.nabu.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/auth/mfa/confirm}.
 *
 * @param code the 6-digit TOTP code from the authenticator app
 */
public record MfaConfirmRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "must be a 6-digit code")
        String code
) {
}
