package watch.nabu.infrastructure.web.dto;

import java.util.List;

/**
 * Request body for PUT /api/v1/admin/compliance/banner-keywords/{regionCode}/{keywordType}.
 * Replaces the full set of keywords for that region + type combination.
 */
public record UpdateBannerKeywordsRequest(List<String> keywords) {
}
