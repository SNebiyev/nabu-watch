package watch.nabu.application.exception;

/**
 * Thrown when a user attempts a manual re-scan before the
 * {@code nabu.scan.manual-rescan-cooldown-minutes} window has elapsed since the last manual scan.
 *
 * <p>Maps to HTTP 429 Too Many Requests via {@code GlobalExceptionHandler}.
 * The message tells the user when they can scan again.
 */
public class ManualRescanCooldownException extends RuntimeException {

    private final long minutesRemaining;
    private final long secondsRemaining;

    public ManualRescanCooldownException(long minutesRemaining) {
        super(buildMessage(minutesRemaining));
        this.minutesRemaining = minutesRemaining;
        // Expose seconds for the Retry-After HTTP header (standard unit)
        this.secondsRemaining = minutesRemaining * 60;
    }

    private static String buildMessage(long minutesRemaining) {
        String when = minutesRemaining <= 1
                ? "in less than a minute"
                : "in " + minutesRemaining + " minute" + (minutesRemaining == 1 ? "" : "s");
        return "You can manually scan this website again " + when + ".";
    }

    public long getMinutesRemaining() {
        return minutesRemaining;
    }

    public long getSecondsRemaining() {
        return secondsRemaining;
    }
}
