package watch.nabu.domain.model;

import org.junit.jupiter.api.Test;
import watch.nabu.domain.model.ScanLog.Severity;

import static org.assertj.core.api.Assertions.assertThat;

class ScanStatusChangeTest {

    @Test
    void nullPrevious_returnsFirstScan() {
        assertThat(ScanStatusChange.compute(null, Severity.INFO))
                .isEqualTo(ScanStatusChange.FIRST_SCAN);
        assertThat(ScanStatusChange.compute(null, Severity.CRITICAL))
                .isEqualTo(ScanStatusChange.FIRST_SCAN);
    }

    @Test
    void sameSeverity_returnsUnchanged() {
        assertThat(ScanStatusChange.compute(Severity.INFO, Severity.INFO))
                .isEqualTo(ScanStatusChange.UNCHANGED);
        assertThat(ScanStatusChange.compute(Severity.HIGH, Severity.HIGH))
                .isEqualTo(ScanStatusChange.UNCHANGED);
    }

    @Test
    void failingBucketToPassingBucket_returnsRecovered() {
        // MEDIUM (FAILING) → INFO (PASSING)
        assertThat(ScanStatusChange.compute(Severity.MEDIUM, Severity.INFO))
                .isEqualTo(ScanStatusChange.RECOVERED);
        // CRITICAL (FAILING) → LOW (PASSING)
        assertThat(ScanStatusChange.compute(Severity.CRITICAL, Severity.LOW))
                .isEqualTo(ScanStatusChange.RECOVERED);
        // HIGH (FAILING) → INFO (PASSING)
        assertThat(ScanStatusChange.compute(Severity.HIGH, Severity.INFO))
                .isEqualTo(ScanStatusChange.RECOVERED);
    }

    @Test
    void passingBucketToFailingBucket_returnsRegressedToFailing() {
        // INFO (PASSING) → MEDIUM (FAILING)
        assertThat(ScanStatusChange.compute(Severity.INFO, Severity.MEDIUM))
                .isEqualTo(ScanStatusChange.REGRESSED_TO_FAILING);
        // LOW (PASSING) → CRITICAL (FAILING)
        assertThat(ScanStatusChange.compute(Severity.LOW, Severity.CRITICAL))
                .isEqualTo(ScanStatusChange.REGRESSED_TO_FAILING);
    }

    @Test
    void withinFailingBucket_higherOrdinal_returnsRegressed() {
        // HIGH → CRITICAL: still FAILING, got worse
        assertThat(ScanStatusChange.compute(Severity.HIGH, Severity.CRITICAL))
                .isEqualTo(ScanStatusChange.REGRESSED);
        // MEDIUM → HIGH: still FAILING, got worse
        assertThat(ScanStatusChange.compute(Severity.MEDIUM, Severity.HIGH))
                .isEqualTo(ScanStatusChange.REGRESSED);
    }

    @Test
    void withinFailingBucket_lowerOrdinal_returnsImproved() {
        // CRITICAL → HIGH: still FAILING, got better
        assertThat(ScanStatusChange.compute(Severity.CRITICAL, Severity.HIGH))
                .isEqualTo(ScanStatusChange.IMPROVED);
        // HIGH → MEDIUM: still FAILING, got better
        assertThat(ScanStatusChange.compute(Severity.HIGH, Severity.MEDIUM))
                .isEqualTo(ScanStatusChange.IMPROVED);
    }

    @Test
    void withinPassingBucket_transitionsAreImprovedOrRegressed() {
        // INFO → LOW: still PASSING, slightly worse
        assertThat(ScanStatusChange.compute(Severity.INFO, Severity.LOW))
                .isEqualTo(ScanStatusChange.REGRESSED);
        // LOW → INFO: still PASSING, slightly better
        assertThat(ScanStatusChange.compute(Severity.LOW, Severity.INFO))
                .isEqualTo(ScanStatusChange.IMPROVED);
    }
}
