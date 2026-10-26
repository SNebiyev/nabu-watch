package watch.nabu.infrastructure.web.dto;

import java.util.List;

/**
 * A group of banner keywords that share the same region and keyword type.
 *
 * <p>{@code regionCode} is {@code "GLOBAL"} for the rows with
 * {@code banner_keywords.region_id IS NULL} (English fallback applied to
 * every scan).  Otherwise it is the ISO 3166-1 alpha-2 code of the region.
 */
public record BannerKeywordGroupResponse(
        String regionCode,
        String regionName,
        String keywordType,
        List<String> keywords
) {
}
