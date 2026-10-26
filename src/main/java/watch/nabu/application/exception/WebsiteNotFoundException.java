package watch.nabu.application.exception;

import java.util.UUID;

public class WebsiteNotFoundException extends RuntimeException {

    public WebsiteNotFoundException(UUID websiteId) {
        super("Website not found: " + websiteId);
    }
}
