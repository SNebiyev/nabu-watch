package watch.nabu.infrastructure.web.dto;

import java.util.List;

public record UpdateGlobalFallbackRequest(List<String> keywords) {
}
