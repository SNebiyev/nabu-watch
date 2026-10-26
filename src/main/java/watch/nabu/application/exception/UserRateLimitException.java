package watch.nabu.application.exception;

public class UserRateLimitException extends RuntimeException {

    public UserRateLimitException() {
        super("Rescan rate limit exceeded (5/min)");
    }
}
