package watch.nabu.application.exception;

public class DomainCooldownException extends RuntimeException {

    private final long secondsRemaining;

    public DomainCooldownException(String domain, long secondsRemaining) {
        super("Domain '" + domain + "' is on cooldown. " + humanReadable(secondsRemaining));
        this.secondsRemaining = secondsRemaining;
    }

    private static String humanReadable(long seconds) {
        if (seconds >= 60) {
            long minutes = (seconds + 59) / 60; // round up so we never say "0 minutes"
            return "Retry in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
        }
        return "Retry in " + seconds + " second" + (seconds == 1 ? "" : "s") + ".";
    }

    public long getSecondsRemaining() {
        return secondsRemaining;
    }

    /**
     * Returns "Retry in X minutes" when ≥ 60 s, otherwise "Retry in X seconds".
     */
    public String getHumanReadableRetry() {
        return humanReadable(secondsRemaining);
    }
}
