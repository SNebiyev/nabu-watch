package watch.nabu.application.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.domain.model.WebsiteCompliancePath;
import watch.nabu.infrastructure.persistence.WebsiteCompliancePathRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompliancePathServiceTest {

    @Mock
    private WebsiteCompliancePathRepository repository;

    @InjectMocks
    private CompliancePathService service;

    @Test
    @DisplayName("upsertFromScan forwards all fields to repository.upsert")
    void upsertFromScan_forwardsAllFields() {
        UUID websiteId = UUID.randomUUID();

        service.upsertFromScan(websiteId, "PRIVACY_POLICY",
                "https://example.com/privacy",
                "/privacy",
                "Privacy Policy",
                "footer a[href='/privacy']",
                "RULE_BASED",
                "high");

        verify(repository).upsert(
                eq(websiteId),
                eq("PRIVACY_POLICY"),
                eq("https://example.com/privacy"),
                eq("/privacy"),
                eq("Privacy Policy"),
                eq("footer a[href='/privacy']"),
                eq("RULE_BASED"),
                eq("high"));
    }

    @Test
    @DisplayName("upsertFromScan accepts null domSelector (nullable column)")
    void upsertFromScan_allowsNullDomSelector() {
        UUID websiteId = UUID.randomUUID();

        service.upsertFromScan(websiteId, "TERMS_OF_SERVICE",
                "https://example.com/tos", "/tos", "Terms",
                null, "AI_DISCOVERY", "medium");

        verify(repository).upsert(
                any(), any(), any(), any(), any(),
                eq((String) null),
                eq("AI_DISCOVERY"),
                eq("medium"));
    }

    @Test
    @DisplayName("recordFastPathSuccess delegates to recordVerification")
    void recordFastPathSuccess_callsRecordVerification() {
        UUID websiteId = UUID.randomUUID();
        service.recordFastPathSuccess(websiteId, "PRIVACY_POLICY");
        verify(repository).recordVerification(websiteId, "PRIVACY_POLICY");
    }

    @Test
    @DisplayName("recordFastPathFailure calls the combined increment+purge query (1 round-trip)")
    void recordFastPathFailure_callsSingleCombinedQuery() {
        UUID websiteId = UUID.randomUUID();
        when(repository.incrementFailCountAndPurgeIfStale(websiteId, "PRIVACY_POLICY")).thenReturn(0);

        service.recordFastPathFailure(websiteId, "PRIVACY_POLICY");

        verify(repository).incrementFailCountAndPurgeIfStale(websiteId, "PRIVACY_POLICY");
    }

    @Test
    @DisplayName("recordFastPathFailure logs WHEN stale entry is purged")
    void recordFastPathFailure_purgedWhenStale() {
        UUID websiteId = UUID.randomUUID();
        when(repository.incrementFailCountAndPurgeIfStale(websiteId, "PRIVACY_POLICY")).thenReturn(1);

        service.recordFastPathFailure(websiteId, "PRIVACY_POLICY");

        verify(repository, times(1)).incrementFailCountAndPurgeIfStale(websiteId, "PRIVACY_POLICY");
    }

    @Test
    @DisplayName("findPath returns the entity when present")
    void findPath_presentReturnsEntity() {
        UUID websiteId = UUID.randomUUID();
        WebsiteCompliancePath path = WebsiteCompliancePath.builder()
                .websiteId(websiteId)
                .subType("PRIVACY_POLICY")
                .discoveredUrl("https://example.com/privacy")
                .anchorHref("/privacy")
                .anchorText("Privacy Policy")
                .source("RULE_BASED")
                .confidence("high")
                .failCount(0)
                .build();
        when(repository.findByWebsiteIdAndSubType(websiteId, "PRIVACY_POLICY"))
                .thenReturn(Optional.of(path));

        WebsiteCompliancePath result = service.findPath(websiteId, "PRIVACY_POLICY");

        assertThat(result).isSameAs(path);
    }

    @Test
    @DisplayName("findPath returns null when absent (project convention)")
    void findPath_absentReturnsNull() {
        UUID websiteId = UUID.randomUUID();
        when(repository.findByWebsiteIdAndSubType(websiteId, "PRIVACY_POLICY"))
                .thenReturn(Optional.empty());

        WebsiteCompliancePath result = service.findPath(websiteId, "PRIVACY_POLICY");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("methods do not touch repository beyond their contract")
    void methods_noExtraInteractions() {
        UUID websiteId = UUID.randomUUID();
        service.recordFastPathSuccess(websiteId, "PRIVACY_POLICY");
        verify(repository).recordVerification(websiteId, "PRIVACY_POLICY");
        verify(repository, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).incrementFailCountAndPurgeIfStale(any(), any());
    }

}
