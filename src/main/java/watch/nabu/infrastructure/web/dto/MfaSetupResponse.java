package watch.nabu.infrastructure.web.dto;

/**
 * Response from {@code POST /api/v1/auth/mfa/setup}.
 *
 * @param secret        the Base32 TOTP secret — shown once for manual entry into an authenticator app
 * @param qrUri         the {@code otpauth://totp/...} URI (for clients that prefer to render QR client-side)
 * @param qrImageBase64 server-rendered 200×200 QR code PNG, Base64-encoded;
 *                      embed directly as {@code <img src="data:image/png;base64,{value}">};
 *                      {@code null} when QR generation failed (URI fallback is still usable)
 */
public record MfaSetupResponse(String secret, String qrUri, String qrImageBase64) {
}
