package watch.nabu.application.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.redis.TaskProducer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitorDispatchServiceTest {

    @Mock MonitorRepository monitorRepository;
    @Mock TaskProducer      taskProducer;

    @InjectMocks MonitorDispatchService dispatchService;

    private Monitor monitor;

    private void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    @BeforeEach
    void setUp() {
        Website website = Website.builder()
            .url("https://example.de")
            .regionCode("DE")
            .build();
        setId(website, UUID.randomUUID());

        monitor = Monitor.builder()
            .website(website)
            .type(MonitorType.SSL)
            .subType(SubType.SSL)
            .intervalMinutes(60)
            .build();
        setId(monitor, UUID.randomUUID());

        lenient().when(monitorRepository.save(any(Monitor.class))).thenReturn(monitor);
    }

    @Test
    void successfulEnqueue_returnsTrueAndStampsLastRunAt() {
        boolean result = dispatchService.dispatch(monitor);

        assertThat(result).isTrue();
        verify(taskProducer).enqueue(monitor, null, false);
        verify(monitorRepository).save(monitor);
        assertThat(monitor.getLastRunAt()).isNotNull();
    }

    @Test
    void redisEnqueueFails_returnsFalseAndDoesNotUpdateLastRunAt() {
        doThrow(new RuntimeException("Redis unavailable")).when(taskProducer).enqueue(any(), any(), anyBoolean());

        boolean result = dispatchService.dispatch(monitor);

        assertThat(result).isFalse();
        // lastRunAt must NOT be set — monitor stays overdue for the next tick
        assertThat(monitor.getLastRunAt()).isNull();
        verify(monitorRepository, never()).save(any());
    }

    @Test
    void dbSaveFailsAfterSuccessfulEnqueue_returnsTrueEnqueueAlreadyHappened() {
        when(monitorRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        // Should return true because the Redis XADD succeeded
        boolean result = dispatchService.dispatch(monitor);

        assertThat(result).isTrue();
        verify(taskProducer).enqueue(monitor, null, false); // confirmed enqueued
    }
}
