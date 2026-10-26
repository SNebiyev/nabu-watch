package watch.nabu.application.scan;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@link ScanResultProcessor} when the last monitor in a manual
 * rescan batch has reported its result and the database transaction has committed.
 *
 * <p>Listeners should use
 * {@link org.springframework.transaction.event.TransactionalEventListener} with
 * {@code phase = AFTER_COMMIT} so they see a fully consistent database state.
 */
@Getter
public class WebsiteBatchCompleteEvent extends ApplicationEvent {

    private final UUID websiteId;
    private final String websiteUrl;
    private final UUID organizationId;

    public WebsiteBatchCompleteEvent(Object source, UUID websiteId, String websiteUrl,
                                     UUID organizationId) {
        super(source);
        this.websiteId = websiteId;
        this.websiteUrl = websiteUrl;
        this.organizationId = organizationId;
    }
}
