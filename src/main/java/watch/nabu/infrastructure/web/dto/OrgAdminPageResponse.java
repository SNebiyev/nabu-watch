package watch.nabu.infrastructure.web.dto;

import java.util.List;

public record OrgAdminPageResponse(
        List<OrgAdminItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
