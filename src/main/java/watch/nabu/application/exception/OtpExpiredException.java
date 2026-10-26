package watch.nabu.application.exception;

/**
 * Thrown when a delete-confirmation OTP is absent, mismatched, or has expired
 * (Redis TTL elapsed).  Maps to HTTP 422 via {@link watch.nabu.infrastructure.web.GlobalExceptionHandler}.
 */
public class OtpExpiredException extends RuntimeException {
    public OtpExpiredException() {
        super("Delete confirmation code is invalid or has expired. Please request a new one.");
    }
}
