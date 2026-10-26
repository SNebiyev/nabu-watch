package watch.nabu.application.scan;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published by {@link ScanResultProcessor} every time a scan result is persisted,
 * regardless of whether the scan was part of a batch or a standalone scheduled scan.
 *
 * <p>This guarantees that SSE-connected dashboards receive a {@code website-updated}
 * signal for every state change, enabling real-time Matrix table updates.
 */
@Getter
public class WebsiteStatusChangedEvent extends ApplicationEvent {

    private final UUID websiteId;
    private final String websiteUrl;
    private final UUID organizationId;
    private final String complianceGrade;
    private final Integer complianceScore;

    public WebsiteStatusChangedEvent(Object source, UUID websiteId, String websiteUrl,
                                     UUID organizationId,
                                     String complianceGrade, Integer complianceScore) {
        super(source);
        this.websiteId = websiteId;
        this.websiteUrl = websiteUrl;
        this.organizationId = organizationId;
        this.complianceGrade = complianceGrade;
        this.complianceScore = complianceScore;
    }
}
