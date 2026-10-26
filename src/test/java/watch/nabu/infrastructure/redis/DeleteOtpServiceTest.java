package watch.nabu.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watch.nabu.application.exception.OtpExpiredException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteOtpServiceTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    watch.nabu.application.messaging.MessageDispatcher messageDispatcher;

    @InjectMocks
    DeleteOtpService deleteOtpService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generate stores a 6-digit OTP in Redis with 10-minute TTL")
    void generate_storesOtpInRedisWithTtl() {
        UUID websiteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        deleteOtpService.generate(websiteId, adminId, "admin@example.com");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        // Key follows the expected pattern
        assertThat(keyCaptor.getValue())
                .isEqualTo("otp:del:" + websiteId + ":" + adminId);

        // Value is a 6-digit string
        String storedCode = valueCaptor.getValue();
        assertThat(storedCode).matches("\\d{6}");

        // TTL is 10 minutes
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("generate emails the code to the admin")
    void generate_sendsEmail() {
        deleteOtpService.generate(UUID.randomUUID(), UUID.randomUUID(), "admin@example.com");
        verify(messageDispatcher).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("generate fails with OtpDeliveryException (and drops the stored code) when email dispatch throws")
    void generate_deliveryFailure_throwsAndDropsCode() {
        UUID websiteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        doThrow(new RuntimeException("smtp down"))
                .when(messageDispatcher).dispatch(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> deleteOtpService.generate(websiteId, adminId, "admin@example.com"))
                .isInstanceOf(watch.nabu.application.exception.OtpDeliveryException.class)
                .hasMessageContaining("Could not send the confirmation code");

        verify(redis).delete("otp:del:" + websiteId + ":" + adminId);
    }

    // ── verify happy path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("verify with matching code → consumes Redis key (single-use)")
    void verify_matchingCode_deletesKey() {
        UUID websiteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String expectedKey = "otp:del:" + websiteId + ":" + adminId;

        when(valueOps.get(expectedKey)).thenReturn("123456");

        assertThatCode(() ->
                deleteOtpService.verify(websiteId, adminId, "123456")
        ).doesNotThrowAnyException();

        verify(redis).delete(expectedKey);
    }

    // ── verify error cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("verify with mismatched code → throws OtpExpiredException, key not deleted")
    void verify_mismatchedCode_throwsOtpExpiredException() {
        UUID websiteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String key = "otp:del:" + websiteId + ":" + adminId;

        when(valueOps.get(key)).thenReturn("999999");

        assertThatThrownBy(() ->
                deleteOtpService.verify(websiteId, adminId, "000000")
        ).isInstanceOf(OtpExpiredException.class);

        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("verify with expired/missing key (null) → throws OtpExpiredException")
    void verify_missingKey_throwsOtpExpiredException() {
        UUID websiteId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String key = "otp:del:" + websiteId + ":" + adminId;

        when(valueOps.get(key)).thenReturn(null);

        assertThatThrownBy(() ->
                deleteOtpService.verify(websiteId, adminId, "123456")
        ).isInstanceOf(OtpExpiredException.class);

        verify(redis, never()).delete(anyString());
    }
}
