package watch.nabu.infrastructure.redis;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class AuthRateLimitException extends RuntimeException {
    public AuthRateLimitException(String message) {
        super(message);
    }
}
