package watch.nabu.application.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.WebsiteCompliancePath;
import watch.nabu.infrastructure.persistence.WebsiteCompliancePathRepository;

import java.util.UUID;

/**
 * Application service for managing persisted compliance document paths.
 *
 * <p>Paths are discovered by the Go scanner (rule-based or AI) and written here
 * via {@link #upsertFromScan}.  On the next scan cycle,
 * {@link watch.nabu.infrastructure.redis.TaskProducer} calls {@link #findPath} to
 * inject them as fast-path hints so the worker can verify the known URL before
 * falling back to a full homepage crawl + AI discovery cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompliancePathService {

    private final WebsiteCompliancePathRepository compliancePathRepository;

    /**
     * Records or upgrades a discovered compliance document path.
     *
     * <p>The underlying UPSERT is confidence-rank gated: a lower-confidence entry
     * will never overwrite a higher-confidence stored row.
     *
     * @param websiteId     the website the path belongs to
     * @param subType       monitor sub-type name (e.g. {@code "PRIVACY_POLICY"})
     * @param discoveredUrl absolute URL of the compliance document
     * @param anchorHref    raw {@code href} from the homepage {@code <a>} element
     * @param anchorText    visible link label
     * @param domSelector   CSS selector for the homepage link (nullable)
     * @param source        discovery method: {@code RULE_BASED}, {@code AI_SEMANTIC_VERIFIED}, or {@code AI_DISCOVERY}
     * @param confidence    confidence tier: {@code high}, {@code medium}, or {@code low}
     */
    @Transactional
    public void upsertFromScan(UUID websiteId, String subType, String discoveredUrl,
                               String anchorHref, String anchorText, String domSelector,
                               String source, String confidence) {
        compliancePathRepository.upsert(
                websiteId, subType, discoveredUrl, anchorHref, anchorText,
                domSelector, source, confidence);
        log.debug("[compliance-path] upsert: websiteId={} subType={} source={} confidence={}",
                websiteId, subType, source, confidence);
    }

    /**
     * Called when fast-path verification succeeded — refreshes {@code last_verified_at}
     * and resets {@code fail_count} to 0.
     */
    @Transactional
    public void recordFastPathSuccess(UUID websiteId, String subType) {
        compliancePathRepository.recordVerification(websiteId, subType);
        log.debug("[compliance-path] fast-path verified: websiteId={} subType={}", websiteId, subType);
    }

    /**
     * Called when fast-path verification failed — increments {@code fail_count} and
     * auto-deletes the stale entry when the threshold of 3 is reached.
     */
    @Transactional
    public void recordFastPathFailure(UUID websiteId, String subType) {
        int deleted = compliancePathRepository.incrementFailCountAndPurgeIfStale(websiteId, subType);
        if (deleted > 0) {
            log.info("[compliance-path] stale path purged after 3 failures: websiteId={} subType={}",
                    websiteId, subType);
        } else {
            log.debug("[compliance-path] fast-path failure recorded: websiteId={} subType={}", websiteId, subType);
        }
    }

    /**
     * Lookup for TaskProducer. Returns {@code null} if no path is stored for this
     * website + subType combination.
     */
    @Transactional(readOnly = true)
    public WebsiteCompliancePath findPath(UUID websiteId, String subType) {
        return compliancePathRepository.findByWebsiteIdAndSubType(websiteId, subType).orElse(null);
    }
}
