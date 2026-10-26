package watch.nabu.application.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.j256.twofactorauth.TimeBasedOneTimePasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

/**
 * TOTP operations: secret generation, QR URI + PNG creation, and code validation.
 *
 * <p>Uses {@code com.j256.two-factor-auth} (zero-dependency, no transitive CVEs)
 * for all TOTP cryptography, and {@code com.google.zxing} 3.5.3+ for server-side
 * QR code PNG generation.
 */
@Slf4j
@Service
public class TotpService {

    private static final String ISSUER = "Nabu Watch";

    /**
     * Window millis passed to {@link TimeBasedOneTimePasswordUtil#validateCurrentNumber}.
     * 15 000 ms = ½ of one 30-second time step — tolerates reasonable clock skew
     * between the client device and the server without opening a two-step window.
     */
    private static final long WINDOW_MILLIS = 15_000L;

    private static final int QR_SIZE_PX = 200;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a cryptographically random Base32 TOTP secret (160-bit).
     */
    public String generateSecret() {
        return TimeBasedOneTimePasswordUtil.generateBase32Secret();
    }

    /**
     * Builds the standard {@code otpauth://totp/...} URI that authenticator apps
     * (Google Authenticator, Authy, etc.) expect when scanning a QR code.
     */
    public String getQrUri(String email, String secret) {
        String encodedIssuer = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        String encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedEmail
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /**
     * Renders the QR code for the given URI as a 200×200 PNG and returns it
     * Base64-encoded so the frontend can embed it directly as
     * {@code <img src="data:image/png;base64,...">}.
     *
     * @param uri the {@code otpauth://} URI produced by {@link #getQrUri}
     * @return Base64-encoded PNG bytes, or {@code null} when encoding fails
     */
    public String generateQrPngBase64(String uri) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(
                    uri,
                    BarcodeFormat.QR_CODE,
                    QR_SIZE_PX, QR_SIZE_PX,
                    Map.of(EncodeHintType.MARGIN, 1)   // 1-module quiet zone (compact)
            );
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            log.error("Failed to generate QR code PNG for MFA setup: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns {@code true} when {@code code} is a valid 6-digit TOTP for the given
     * Base32 secret at the current system time.
     *
     * <p>Leading-zero codes (e.g. "012345") are handled correctly: the string is
     * parsed as a decimal integer before validation, which is what the TOTP spec
     * requires (the 6-digit value, not the zero-padded string).
     */
    public boolean isCodeValid(String secret, String code) {
        if (code == null || code.length() != 6) return false;
        try {
            int number = Integer.parseInt(code);
            return TimeBasedOneTimePasswordUtil.validateCurrentNumber(secret, number, WINDOW_MILLIS);
        } catch (NumberFormatException e) {
            return false;
        } catch (GeneralSecurityException e) {
            log.warn("TOTP code validation error: {}", e.getMessage());
            return false;
        }
    }
}
