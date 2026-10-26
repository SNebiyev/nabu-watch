package watch.nabu.application.exception;

/**
 * Thrown when the delete-confirmation OTP email could not be dispatched. Maps to HTTP 503 via
 * {@link watch.nabu.infrastructure.web.GlobalExceptionHandler}.
 */
public class OtpDeliveryException extends RuntimeException {
    public OtpDeliveryException(Throwable cause) {
        super("Could not send the confirmation code. Check the mail settings.", cause);
    }
}
