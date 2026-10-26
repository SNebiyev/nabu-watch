package watch.nabu.infrastructure.web;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import watch.nabu.application.exception.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("MaliciousPayloadException → 400 with generic message")
    void maliciousPayload_returns400() {
        ResponseEntity<ProblemDetail> response = handler.handleMaliciousPayload(
                new MaliciousPayloadException("xss detected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).contains("security policy");
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "MALICIOUS_PAYLOAD_BLOCKED");
    }

    @Test
    @DisplayName("PendingVerificationLimitException → 429 PENDING_VERIFICATION_LIMIT with limit and window")
    void pendingVerificationLimit_returns429() {
        ResponseEntity<ProblemDetail> response = handler.handlePendingVerificationLimit(
                new PendingVerificationLimitException(5, 30));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "PENDING_VERIFICATION_LIMIT");
        assertThat(response.getBody().getProperties()).containsEntry("limit", 5);
        assertThat(response.getBody().getProperties()).containsEntry("windowDays", 30);
    }

    @Test
    @DisplayName("UserRateLimitException → 429 with Retry-After header")
    void userRateLimit_returns429() {
        ResponseEntity<ProblemDetail> response = handler.handleUserRateLimit(
                new UserRateLimitException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("MfaChallengeRequiredException → 403 with MFA_CHALLENGE_REQUIRED")
    void mfaChallenge_returns403() {
        ProblemDetail result = handler.handleMfaChallenge(
                new MfaChallengeRequiredException());

        assertThat(result.getStatus()).isEqualTo(403);
        assertThat(result.getProperties()).containsEntry("errorCode", "MFA_CHALLENGE_REQUIRED");
    }

    @Test
    @DisplayName("MfaInvalidException → 403 with MFA_INVALID")
    void mfaInvalid_returns403() {
        ProblemDetail result = handler.handleMfaInvalid(
                new MfaInvalidException());

        assertThat(result.getStatus()).isEqualTo(403);
        assertThat(result.getProperties()).containsEntry("errorCode", "MFA_INVALID");
    }

    @Test
    @DisplayName("OtpExpiredException → 422 with OTP_INVALID")
    void otpExpired_returns422() {
        ProblemDetail result = handler.handleOtpExpired(new OtpExpiredException());

        assertThat(result.getStatus()).isEqualTo(422);
        assertThat(result.getProperties()).containsEntry("errorCode", "OTP_INVALID");
    }

    @Test
    @DisplayName("DomainMismatchException → 422 with DOMAIN_MISMATCH")
    void domainMismatch_returns422() {
        ProblemDetail result = handler.handleDomainMismatch(
                new DomainMismatchException("example.com", "evil.com"));

        assertThat(result.getStatus()).isEqualTo(422);
        assertThat(result.getProperties()).containsEntry("errorCode", "DOMAIN_MISMATCH");
    }

    @Test
    @DisplayName("UserNotFoundException → 404")
    void userNotFound_returns404() {
        ProblemDetail result = handler.handleUserNotFound(
                new UserNotFoundException("user@example.com"));

        assertThat(result.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("MonitorNotFoundException → 404")
    void monitorNotFound_returns404() {
        ProblemDetail result = handler.handleMonitorNotFound(
                new MonitorNotFoundException(java.util.UUID.randomUUID()));

        assertThat(result.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("WebsiteNotFoundException → 404")
    void websiteNotFound_returns404() {
        ProblemDetail result = handler.handleWebsiteNotFound(
                new WebsiteNotFoundException(java.util.UUID.randomUUID()));

        assertThat(result.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("DataIntegrityViolation with duplicate website → 409")
    void duplicateWebsite_returns409() {
        ProblemDetail result = handler.handleDataIntegrity(
                new DataIntegrityViolationException("uq_websites_user_url constraint violation"));

        assertThat(result.getStatus()).isEqualTo(409);
        assertThat(result.getDetail()).contains("already exists");
    }

    @Test
    @DisplayName("DataIntegrityViolation without website constraint → re-throws")
    void otherDataIntegrity_rethrows() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("other constraint");

        assertThatThrownBy(() -> handler.handleDataIntegrity(ex))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ManualRescanCooldownException → 429 with Retry-After")
    void manualRescanCooldown_returns429() {
        ResponseEntity<ProblemDetail> response = handler.handleManualRescanCooldown(
                new ManualRescanCooldownException(30));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "MANUAL_RESCAN_COOLDOWN");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("DomainCooldownException → 429 with Retry-After")
    void domainCooldown_returns429() {
        ResponseEntity<ProblemDetail> response = handler.handleDomainCooldown(
                new DomainCooldownException("example.com", 120));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "DOMAIN_COOLDOWN");
    }

    // ─── Client disconnect (SSE churn) ───────────────────────────────────────
    // Regression cover for prod log noise: a closed browser tab on
    // /api/v1/scans/stream reached handleUnexpected(), which tried to write a
    // ProblemDetail into a text/event-stream response and blew up with
    // "No converter for ProblemDetail with preset Content-Type".

    @Test
    @DisplayName("ClientAbortException → handled quietly, no response written")
    void clientAbort_isSwallowed() {
        // Must not throw. void return = handled, nothing written to the socket.
        handler.handleClientAbort(new ClientAbortException("Broken pipe"));
    }

    @Test
    @DisplayName("Broken pipe wrapped in a RuntimeException → no 500 body")
    void wrappedClientAbort_returnsNoBody() throws Exception {
        Exception wrapped = new RuntimeException("write failed",
                new ClientAbortException(new IOException("Broken pipe")));

        assertThat(handler.handleUnexpected(wrapped)).isNull();
    }

    @Test
    @DisplayName("Bare IOException 'Broken pipe' is recognised as a disconnect")
    void bareBrokenPipe_isDisconnect() {
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new IOException("Broken pipe"))).isTrue();
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new IOException("Connection reset by peer"))).isTrue();
    }

    @Test
    @DisplayName("AsyncRequestNotUsableException matched by simple name, not import")
    void asyncRequestNotUsable_isDisconnect() {
        // Named to mirror Spring 6.2's class; matching by simple name keeps the
        // handler compiling across Spring versions that lack it.
        class AsyncRequestNotUsableException extends RuntimeException {
            AsyncRequestNotUsableException(String m) { super(m); }
        }
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new AsyncRequestNotUsableException("ServletOutputStream failed to write"))).isTrue();
    }

    @Test
    @DisplayName("A genuine IOException is NOT treated as a disconnect")
    void unrelatedIOException_stillReturns500() throws Exception {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
                new IOException("disk full"));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).contains("disk full");
    }

    @Test
    @DisplayName("Self-referential cause chain terminates instead of looping")
    void selfReferentialCause_doesNotHang() {
        Exception loop = new IOException("nothing to see here") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertThat(GlobalExceptionHandler.isClientDisconnect(loop)).isFalse();
    }
}
