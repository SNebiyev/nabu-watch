package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import watch.nabu.domain.model.SecurityScan;
import watch.nabu.domain.model.Website;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityScanAccessServiceTest {

    private final SecurityScanAccessService service = new SecurityScanAccessService();

    private static Website website(boolean verified) {
        Website w = new Website();
        if (verified) w.setVerifiedAt(Instant.now());
        return w;
    }

    @Test
    void unverifiedWebsiteLocksEveryShippedScan() {
        List<SecurityScanAccessService.ScanStatus> out = service.list(website(false));

        assertThat(out).hasSize(SecurityScan.values().length);
        for (SecurityScanAccessService.ScanStatus s : out) {
            assertThat(s.enabled()).isFalse();
            assertThat(s.lockReason()).isEqualTo("VERIFIED_REQUIRED");
        }
    }

    @Test
    void verifiedWebsiteUnlocksEveryShippedScanWithoutAnyPlan() {
        List<SecurityScanAccessService.ScanStatus> out = service.list(website(true));

        for (SecurityScanAccessService.ScanStatus s : out) {
            if (s.scan().shipped()) {
                assertThat(s.enabled()).as(s.scan().key()).isTrue();
                assertThat(s.lockReason()).isNull();
            }
        }
    }

    @Test
    void isAllowedIsShippedAndVerified() {
        for (SecurityScan scan : SecurityScan.values()) {
            assertThat(service.isAllowed(website(true), scan)).isEqualTo(scan.shipped());
            assertThat(service.isAllowed(website(false), scan)).isFalse();
        }
    }
}
