package watch.nabu.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;

public record OrgMemberAdminItem(
        UUID userId,
        String fullName,
        String email,
        String role,
        Instant joinedAt
) {
}
