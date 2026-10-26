package watch.nabu.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watch.nabu.application.exception.UserRateLimitException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    @InjectMocks
    RateLimitService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ── checkUserApiBudget ───────────────────────────────────────────────────

    @Test
    @DisplayName("first request seeds the counter and sets the 60-second TTL")
    void firstRequestSeedsTtl() {
        when(valueOps.increment("rl:api:user:alice@example.com")).thenReturn(1L);

        service.checkUserApiBudget("alice@example.com");

        verify(redis).expire("rl:api:user:alice@example.com", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("subsequent requests under the cap do not reset the TTL")
    void subsequentRequestsDontResetTtl() {
        when(valueOps.increment(anyString())).thenReturn(42L);

        service.checkUserApiBudget("alice@example.com");

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("at the boundary (600 requests) the call still passes")
    void boundaryRequestPasses() {
        when(valueOps.increment(anyString())).thenReturn(600L);

        // 600 is the cap — equal to MAX, not strictly greater, so allowed.
        assertThatCode(() -> service.checkUserApiBudget("alice@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the 601st request throws UserRateLimitException")
    void overBudgetThrows() {
        when(valueOps.increment(anyString())).thenReturn(601L);

        assertThatThrownBy(() -> service.checkUserApiBudget("alice@example.com"))
                .isInstanceOf(UserRateLimitException.class);
    }

    @Test
    @DisplayName("null and blank principals are no-ops (anonymous flow)")
    void nullPrincipalIsNoop() {
        service.checkUserApiBudget(null);
        service.checkUserApiBudget("");
        service.checkUserApiBudget("   ");

        verify(valueOps, never()).increment(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
