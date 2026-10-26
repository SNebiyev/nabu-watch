package watch.nabu.application.exception;

/**
 * Thrown by {@link watch.nabu.application.messaging.MessageSecurityValidator}
 * when a message body contains a known XSS vector or SQL-injection pattern.
 *
 * <p>This exception is a hard rejection signal: the payload is <em>never</em>
 * sanitised and <em>never</em> persisted.  The service layer catches it,
 * fires a {@code SECURITY_ALERT} notification to all admin users, and returns
 * HTTP 400 to the sender with a generic policy message.
 */
public class MaliciousPayloadException extends RuntimeException {

    public MaliciousPayloadException(String message) {
        super(message);
    }
}
