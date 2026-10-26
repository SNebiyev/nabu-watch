package watch.nabu.application.exception;

public class MfaChallengeRequiredException extends RuntimeException {

    public MfaChallengeRequiredException() {
        super("MFA step-up required for force override");
    }
}
