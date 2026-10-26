package watch.nabu.application.exception;

public class DomainMismatchException extends RuntimeException {

    public DomainMismatchException(String allowedDomain, String targetDomain) {
        super("Target domain '" + targetDomain + "' is not allowed for website domain '" + allowedDomain + "'");
    }
}
