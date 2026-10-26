package watch.nabu.application.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.scan.ComplianceGradeCalculator.GradeResult;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.MonitorStatusOverride;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.ScanLog.Severity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceGradeCalculatorTest {

    // ── severity multiplier ─────────────────────────────────────────────────

    private static Monitor monitor(SubType subType) {
        Monitor m = new Monitor();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setType(subType == SubType.SSL ? MonitorType.SSL : MonitorType.COMPLIANCE);
        m.setSubType(subType);
        m.setActive(true);
        return m;
    }

    private static ScanLog scanLog(Monitor monitor, Severity severity) {
        ScanLog sl = ScanLog.builder()
                .monitor(monitor)
                .severity(severity)
                .build();
        ReflectionTestUtils.setField(sl, "id", UUID.randomUUID());
        return sl;
    }

    // ── score to grade ──────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "INFO, 1.0",
            "LOW, 0.8",
            "MEDIUM, 0.5",
            "HIGH, 0.2",
            "CRITICAL, 0.0"
    })
    void severityMultiplier_returnsCorrectValue(Severity severity, double expected) {
        assertThat(ComplianceGradeCalculator.severityMultiplier(severity)).isEqualTo(expected);
    }

    // ── full calculation ────────────────────────────────────────────────────

    @Test
    void severityMultiplier_nullReturnsZero() {
        assertThat(ComplianceGradeCalculator.severityMultiplier(null)).isEqualTo(0.0);
    }

    @ParameterizedTest
    @CsvSource({
            "100, A+", "95, A+",
            "94, A", "90, A",
            "89, A-", "85, A-",
            "84, B+", "80, B+",
            "79, B", "70, B",
            "69, C", "60, C",
            "59, D", "50, D",
            "49, F", "0, F"
    })
    void scoreToGrade_mapsCorrectly(int score, String expectedGrade) {
        assertThat(ComplianceGradeCalculator.scoreToGrade(score)).isEqualTo(expectedGrade);
    }

    @Test
    void allMonitorsPassing_returnsAPlus() {
        List<Monitor> monitors = List.of(
                monitor(SubType.SSL),
                monitor(SubType.PRIVACY_POLICY),
                monitor(SubType.TERMS_OF_SERVICE),
                monitor(SubType.IMPRESSUM),
                monitor(SubType.COOKIE_POLICY),
                monitor(SubType.GDPR_REQUEST),
                monitor(SubType.COOKIE_BANNER)
        );
        Map<UUID, ScanLog> latest = new HashMap<>();
        for (Monitor m : monitors) {
            latest.put(m.getId(), scanLog(m, Severity.INFO));
        }

        GradeResult result = ComplianceGradeCalculator.calculate(monitors, latest);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A+");
    }

    @Test
    void allMonitorsCritical_returnsF() {
        List<Monitor> monitors = List.of(
                monitor(SubType.SSL),
                monitor(SubType.PRIVACY_POLICY),
                monitor(SubType.COOKIE_BANNER)
        );
        Map<UUID, ScanLog> latest = new HashMap<>();
        for (Monitor m : monitors) {
            latest.put(m.getId(), scanLog(m, Severity.CRITICAL));
        }

        GradeResult result = ComplianceGradeCalculator.calculate(monitors, latest);

        assertThat(result.score()).isEqualTo(0);
        assertThat(result.grade()).isEqualTo("F");
    }

    @Test
    void trackerDetection_isExcluded() {
        List<Monitor> monitors = List.of(
                monitor(SubType.SSL),
                monitor(SubType.TRACKER_DETECTION)
        );
        // SSL passes, TRACKER_DETECTION is MEDIUM — but should be ignored
        Map<UUID, ScanLog> latest = Map.of(
                monitors.get(0).getId(), scanLog(monitors.get(0), Severity.INFO),
                monitors.get(1).getId(), scanLog(monitors.get(1), Severity.MEDIUM)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(monitors, latest);

        // Only SSL counts — all weight goes to SSL with INFO → score 100
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A+");
    }

    @Test
    void pendingMonitors_scoreZeroForThatWeight() {
        List<Monitor> monitors = List.of(
                monitor(SubType.SSL),
                monitor(SubType.COOKIE_BANNER)
        );
        // Only SSL has a scan result; COOKIE_BANNER is pending (no entry in map)
        Map<UUID, ScanLog> latest = Map.of(
                monitors.get(0).getId(), scanLog(monitors.get(0), Severity.INFO)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(monitors, latest);

        // SSL weight=20, COOKIE_BANNER weight=20 → total=40
        // SSL: 20/40*100*1.0 = 50, COOKIE_BANNER: 20/40*100*0.0 = 0 → score=50
        assertThat(result.score()).isEqualTo(50);
        assertThat(result.grade()).isEqualTo("D");
    }

    @Test
    void emptyMonitors_returnsF() {
        GradeResult result = ComplianceGradeCalculator.calculate(List.of(), Map.of());
        assertThat(result.score()).isEqualTo(0);
        assertThat(result.grade()).isEqualTo("F");
    }

    @Test
    void inactiveMonitors_areExcluded() {
        Monitor active = monitor(SubType.SSL);
        Monitor inactive = monitor(SubType.COOKIE_BANNER);
        inactive.setActive(false);

        Map<UUID, ScanLog> latest = Map.of(
                active.getId(), scanLog(active, Severity.INFO),
                inactive.getId(), scanLog(inactive, Severity.CRITICAL)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(
                List.of(active, inactive), latest);

        // Only active SSL counts → score 100
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A+");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    void mixedSeverities_calculatesWeightedScore() {
        // SSL(20) = INFO, PRIVACY(15) = MEDIUM, COOKIE_BANNER(20) = HIGH
        Monitor ssl = monitor(SubType.SSL);
        Monitor privacy = monitor(SubType.PRIVACY_POLICY);
        Monitor banner = monitor(SubType.COOKIE_BANNER);
        List<Monitor> monitors = List.of(ssl, privacy, banner);

        Map<UUID, ScanLog> latest = Map.of(
                ssl.getId(), scanLog(ssl, Severity.INFO),
                privacy.getId(), scanLog(privacy, Severity.MEDIUM),
                banner.getId(), scanLog(banner, Severity.HIGH)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(monitors, latest);

        // Weights: SSL=20, PRIVACY=15, BANNER=20 → total=55
        // SSL: 20/55*100*1.0 = 36.36
        // PRIVACY: 15/55*100*0.5 = 13.64
        // BANNER: 20/55*100*0.2 = 7.27
        // Total: 57.27 → rounds to 57
        assertThat(result.score()).isEqualTo(57);
        assertThat(result.grade()).isEqualTo("D");
    }

    @Test
    void subsetOfMonitorTypes_normalisesWeights() {
        // Only SSL(20) and GDPR_REQUEST(10) exist for this website
        Monitor ssl = monitor(SubType.SSL);
        Monitor gdpr = monitor(SubType.GDPR_REQUEST);

        Map<UUID, ScanLog> latest = Map.of(
                ssl.getId(), scanLog(ssl, Severity.INFO),
                gdpr.getId(), scanLog(gdpr, Severity.INFO)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(List.of(ssl, gdpr), latest);

        // Both passing → score 100 (weights normalised to 100%)
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A+");
    }

    // ── override tests ──────────────────────────────────────────────────────

    private static MonitorStatusOverride override(Monitor monitor, Severity baseline, Severity overrideSev) {
        return MonitorStatusOverride.builder()
                .monitor(monitor)
                .baselineSeverity(baseline)
                .overrideSeverity(overrideSev)
                .build();
    }

    @Test
    void override_matchingBaseline_usesOverrideSeverity() {
        Monitor ssl = monitor(SubType.SSL);
        Monitor privacy = monitor(SubType.PRIVACY_POLICY);

        // Privacy has MEDIUM severity but is overridden to INFO
        Map<UUID, ScanLog> latest = Map.of(
                ssl.getId(), scanLog(ssl, Severity.INFO),
                privacy.getId(), scanLog(privacy, Severity.MEDIUM)
        );
        Map<UUID, MonitorStatusOverride> overrides = Map.of(
                privacy.getId(), override(privacy, Severity.MEDIUM, Severity.INFO)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(
                List.of(ssl, privacy), latest, overrides);

        // Both effectively INFO → score 100
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A+");
    }

    @Test
    void override_mismatchBaseline_usesRawSeverity() {
        Monitor ssl = monitor(SubType.SSL);
        Monitor privacy = monitor(SubType.PRIVACY_POLICY);

        // Privacy has HIGH severity but override was set for MEDIUM baseline
        Map<UUID, ScanLog> latest = Map.of(
                ssl.getId(), scanLog(ssl, Severity.INFO),
                privacy.getId(), scanLog(privacy, Severity.HIGH)
        );
        Map<UUID, MonitorStatusOverride> overrides = Map.of(
                privacy.getId(), override(privacy, Severity.MEDIUM, Severity.INFO)
        );

        GradeResult result = ComplianceGradeCalculator.calculate(
                List.of(ssl, privacy), latest, overrides);

        // SSL: INFO=1.0, Privacy: HIGH=0.2 (override not applied — baseline mismatch)
        // Weights: SSL=20, Privacy=15 → total=35
        // SSL: 20/35*100*1.0 = 57.14, Privacy: 15/35*100*0.2 = 8.57 → 66 → C
        assertThat(result.score()).isEqualTo(66);
        assertThat(result.grade()).isEqualTo("C");
    }

    @Test
    void override_emptyMap_behavesLikeNoOverrides() {
        Monitor ssl = monitor(SubType.SSL);
        Map<UUID, ScanLog> latest = Map.of(
                ssl.getId(), scanLog(ssl, Severity.MEDIUM)
        );

        GradeResult withOverrides = ComplianceGradeCalculator.calculate(
                List.of(ssl), latest, Map.of());
        GradeResult without = ComplianceGradeCalculator.calculate(
                List.of(ssl), latest);

        assertThat(withOverrides.score()).isEqualTo(without.score());
        assertThat(withOverrides.grade()).isEqualTo(without.grade());
    }
}
