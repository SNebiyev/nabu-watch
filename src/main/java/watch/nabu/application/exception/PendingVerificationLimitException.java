package watch.nabu.application.exception;

/**
 * Thrown when a user has too many unverified websites queued up within the
 * configured window. Protects the verification queue (and the broader scan
 * infrastructure) from one user mass-adding sites without completing DNS TXT
 * ownership proofs.
 *
 * <p>Maps to {@code HTTP 429} with {@code errorCode = "PENDING_VERIFICATION_LIMIT"}
 * via {@code GlobalExceptionHandler}.
 */
public class PendingVerificationLimitException extends RuntimeException {

    private final int limit;
    private final int windowDays;

    public PendingVerificationLimitException(int limit, int windowDays) {
        super(String.format(
                "You can have at most %d unverified websites in %d days. " +
                        "Complete DNS TXT verification on an existing site first.",
                limit, windowDays));
        this.limit = limit;
        this.windowDays = windowDays;
    }

    public int getLimit() {
        return limit;
    }

    public int getWindowDays() {
        return windowDays;
    }
}
