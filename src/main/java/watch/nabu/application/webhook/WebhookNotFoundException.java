package watch.nabu.application.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

public class WebhookNotFoundException extends ResponseStatusException {

    public WebhookNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Webhook not found: " + id);
    }
}
