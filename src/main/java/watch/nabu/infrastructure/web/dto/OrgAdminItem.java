package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;

public record OrgAdminItem(
        UUID id,
        String name,
        boolean enabled,
        long memberCount,
        long websiteCount,
        Instant createdAt
) {
}
