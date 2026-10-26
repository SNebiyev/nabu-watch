package watch.nabu.application.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.scan.ScanBatchAggregatorPort;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringSchedulerTest {

    @Mock
    MonitorRepository monitorRepository;
    @Mock
    MonitorDispatchService dispatchService;
    @Mock
    ScanBatchAggregatorPort scanBatchAggregator;

    @InjectMocks MonitoringScheduler scheduler;

    @BeforeEach
    void injectBatchSize() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
    }

    private void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    private Monitor makeMonitor() {
        Organization org = Organization.builder().name("Test Org").build();
        setId(org, UUID.randomUUID());

        User user = new User();
        setId(user, UUID.randomUUID());

        Website website = Website.builder()
            .url("https://example.de")
            .regionCode("DE")
                .organization(org)
                .addedBy(user)
            .build();
        setId(website, UUID.randomUUID());

        Monitor m = Monitor.builder()
            .website(website)
            .type(MonitorType.SSL)
            .subType(SubType.SSL)
            .intervalMinutes(60)
            .build();
        setId(m, UUID.randomUUID());
        return m;
    }

    // ── No overdue monitors ───────────────────────────────────────────────────

    @Test
    void noOverdueMonitors_dispatchIsNeverCalled() {
        when(monitorRepository.findOverdueMonitorIds(anyInt())).thenReturn(List.of());

        scheduler.dispatchOverdueMonitors();

        verify(monitorRepository).findOverdueMonitorIds(100);
        verify(monitorRepository, never()).findAllWithWebsiteByIds(any());
        verifyNoInteractions(dispatchService);
    }

    // ── Normal batch ──────────────────────────────────────────────────────────

    @Test
    void threeOverdueMonitors_allDispatched() {
        Monitor m1 = makeMonitor();
        Monitor m2 = makeMonitor();
        Monitor m3 = makeMonitor();
        List<UUID> ids = List.of(m1.getId(), m2.getId(), m3.getId());

        when(monitorRepository.findOverdueMonitorIds(100))
            .thenReturn(ids)
            .thenReturn(List.of());
        when(monitorRepository.findAllWithWebsiteByIds(ids))
            .thenReturn(List.of(m1, m2, m3));
        // Each monitor has its own website → 3 dispatchBatch calls (1 per website)
        when(dispatchService.dispatchBatch(anyList(), any(UUID.class))).thenReturn(new int[]{1, 0});

        scheduler.dispatchOverdueMonitors();

        verify(dispatchService, times(3)).dispatchBatch(anyList(), any(UUID.class));
    }

    // ── Redis-down guard ──────────────────────────────────────────────────────

    @Test
    void allDispatchesFailInBatch_schedulerAbortsAfterFirstBatch() {
        Monitor m1 = makeMonitor();
        Monitor m2 = makeMonitor();
        List<UUID> ids = List.of(m1.getId(), m2.getId());

        when(monitorRepository.findOverdueMonitorIds(100)).thenReturn(ids);
        when(monitorRepository.findAllWithWebsiteByIds(ids)).thenReturn(List.of(m1, m2));
        // All dispatches fail → Redis-down guard triggers
        when(dispatchService.dispatchBatch(anyList(), any(UUID.class))).thenReturn(new int[]{0, 2});

        scheduler.dispatchOverdueMonitors();

        // Scheduler must NOT try a second batch after total failure
        verify(monitorRepository, times(1)).findOverdueMonitorIds(100);
    }

    // ── Partial success continues ──────────────────────────────────────────────

    @Test
    void partialBatchSuccess_schedulerContinuesToNextBatch() {
        Monitor m1 = makeMonitor();
        Monitor m2 = makeMonitor();
        Monitor m3 = makeMonitor();
        List<UUID> batch1Ids = List.of(m1.getId(), m2.getId());
        List<UUID> batch2Ids = List.of(m3.getId());

        when(monitorRepository.findOverdueMonitorIds(100))
            .thenReturn(batch1Ids)
            .thenReturn(batch2Ids)
            .thenReturn(List.of());
        when(monitorRepository.findAllWithWebsiteByIds(batch1Ids)).thenReturn(List.of(m1, m2));
        when(monitorRepository.findAllWithWebsiteByIds(batch2Ids)).thenReturn(List.of(m3));
        // Each monitor has its own website. batch1 has 2 websites.
        // First website fails, second succeeds → partial success, loop continues.
        when(dispatchService.dispatchBatch(anyList(), any(UUID.class)))
                .thenReturn(new int[]{0, 1})  // first website in batch1 fails
                .thenReturn(new int[]{1, 0})  // second website in batch1 succeeds
                .thenReturn(new int[]{1, 0}); // batch2 succeeds

        scheduler.dispatchOverdueMonitors();

        verify(dispatchService, times(3)).dispatchBatch(anyList(), any(UUID.class));
    }
}
