package watch.nabu.application.exception;

public class MfaInvalidException extends RuntimeException {

    public MfaInvalidException() {
        super("Invalid or expired TOTP code");
    }
}
