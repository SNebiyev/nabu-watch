package watch.nabu.application.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.MonitorTypeIntervalConfig;
import watch.nabu.infrastructure.config.ScanLimitsProperties;
import watch.nabu.infrastructure.persistence.MonitorTypeIntervalConfigRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitorIntervalConfigServiceTest {

    @Mock
    MonitorTypeIntervalConfigRepository configRepository;
    @Mock
    ScanLimitsProperties scanLimits;

    @InjectMocks
    MonitorIntervalConfigService service;

    // ── resolveInterval ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSL always returns 1440 regardless of config")
    void resolveInterval_ssl_alwaysFixed() {
        assertThat(service.resolveInterval(SubType.SSL)).isEqualTo(1440);
        verifyNoInteractions(configRepository);
    }

    @Test
    @DisplayName("compliance interval returned from config table")
    void resolveInterval_complianceFromConfig() {
        MonitorTypeIntervalConfig cfg = MonitorTypeIntervalConfig.builder()
                .subTypeCode("PRIVACY_POLICY").autoScanIntervalMinutes(60).build();
        when(configRepository.findById("PRIVACY_POLICY")).thenReturn(Optional.of(cfg));

        assertThat(service.resolveInterval(SubType.PRIVACY_POLICY)).isEqualTo(60);
    }

    @Test
    @DisplayName("falls back to nabu.scan.default-interval-minutes when config row is missing")
    void resolveInterval_fallbackToConfiguredDefault() {
        when(configRepository.findById("IMPRESSUM")).thenReturn(Optional.empty());
        when(scanLimits.defaultIntervalMinutes()).thenReturn(720);

        assertThat(service.resolveInterval(SubType.IMPRESSUM)).isEqualTo(720);
    }

    // ── updateInterval ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateInterval saves new value and propagates to monitors")
    void updateInterval_savesAndPropagates() {
        MonitorTypeIntervalConfig existing = MonitorTypeIntervalConfig.builder()
                .subTypeCode("PRIVACY_POLICY").autoScanIntervalMinutes(1440).build();
        when(configRepository.findById("PRIVACY_POLICY")).thenReturn(Optional.of(existing));
        when(configRepository.save(any(MonitorTypeIntervalConfig.class))).thenAnswer(i -> i.getArgument(0));
        when(configRepository.propagateIntervalToMonitors(SubType.PRIVACY_POLICY, 60)).thenReturn(4);
        when(configRepository.resetLastRunAtForOverdueMonitors("PRIVACY_POLICY", 60)).thenReturn(1);

        MonitorTypeIntervalConfig result = service.updateInterval("PRIVACY_POLICY", 60);

        assertThat(result.getAutoScanIntervalMinutes()).isEqualTo(60);
        verify(configRepository).propagateIntervalToMonitors(SubType.PRIVACY_POLICY, 60);
        verify(configRepository).resetLastRunAtForOverdueMonitors("PRIVACY_POLICY", 60);
    }

    @Test
    @DisplayName("updateInterval creates a row when none exists")
    void updateInterval_createsRow() {
        when(configRepository.findById("IMPRESSUM")).thenReturn(Optional.empty());
        when(configRepository.save(any(MonitorTypeIntervalConfig.class))).thenAnswer(i -> i.getArgument(0));

        MonitorTypeIntervalConfig result = service.updateInterval("IMPRESSUM", 30);

        assertThat(result.getSubTypeCode()).isEqualTo("IMPRESSUM");
        assertThat(result.getAutoScanIntervalMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("updateInterval ignores SSL (fixed at 1440)")
    void updateInterval_sslIgnored() {
        when(configRepository.findById("SSL")).thenReturn(Optional.empty());

        MonitorTypeIntervalConfig result = service.updateInterval("SSL", 5);

        assertThat(result.getAutoScanIntervalMinutes()).isEqualTo(1440);
        verify(configRepository, never()).save(any());
        verify(configRepository, never()).propagateIntervalToMonitors(any(), anyInt());
    }

    @Test
    @DisplayName("updateInterval rejects intervals below 1 minute")
    void updateInterval_rejectsInvalid() {
        assertThatThrownBy(() -> service.updateInterval("IMPRESSUM", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(configRepository);
    }

    @Test
    @DisplayName("updateInterval saves but skips propagation for an unknown sub-type code")
    void updateInterval_unknownSubType_skipsPropagation() {
        when(configRepository.findById("NOPE")).thenReturn(Optional.empty());
        when(configRepository.save(any(MonitorTypeIntervalConfig.class))).thenAnswer(i -> i.getArgument(0));

        service.updateInterval("NOPE", 10);

        verify(configRepository, never()).propagateIntervalToMonitors(any(), anyInt());
    }
}
