package watch.nabu.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorSubTypeTest {

    @ParameterizedTest
    @EnumSource(Monitor.SubType.class)
    @DisplayName("Every SubType has a non-null, non-empty shortLabel")
    void allSubTypes_haveShortLabel(Monitor.SubType subType) {
        assertThat(subType.shortLabel()).isNotNull().isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(Monitor.SubType.class)
    @DisplayName("Every SubType has a non-null, non-empty label")
    void allSubTypes_haveLabel(Monitor.SubType subType) {
        assertThat(subType.label()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("shortLabel values match expected abbreviations")
    void shortLabel_matchesExpected() {
        assertThat(Monitor.SubType.SSL.shortLabel()).isEqualTo("SSL");
        assertThat(Monitor.SubType.PRIVACY_POLICY.shortLabel()).isEqualTo("PRIV");
        assertThat(Monitor.SubType.TERMS_OF_SERVICE.shortLabel()).isEqualTo("TOS");
        assertThat(Monitor.SubType.IMPRESSUM.shortLabel()).isEqualTo("IMPR");
        assertThat(Monitor.SubType.COOKIE_POLICY.shortLabel()).isEqualTo("CKPL");
        assertThat(Monitor.SubType.GDPR_REQUEST.shortLabel()).isEqualTo("GDPR");
        assertThat(Monitor.SubType.COOKIE_BANNER.shortLabel()).isEqualTo("CKBNR");
        assertThat(Monitor.SubType.TRACKER_DETECTION.shortLabel()).isEqualTo("TRKR");
    }

    @Test
    @DisplayName("shortLabel is always shorter than or equal to label")
    void shortLabel_isShorterThanLabel() {
        for (Monitor.SubType subType : Monitor.SubType.values()) {
            assertThat(subType.shortLabel().length())
                    .as("shortLabel for %s should be <= label length", subType)
                    .isLessThanOrEqualTo(subType.label().length());
        }
    }
}
