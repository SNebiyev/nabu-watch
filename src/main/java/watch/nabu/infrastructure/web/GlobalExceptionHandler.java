package watch.nabu.infrastructure.web;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.exception.*;
import watch.nabu.infrastructure.redis.AuthRateLimitException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthRateLimitException.class)
    public ResponseEntity<ProblemDetail> handleAuthRateLimit(AuthRateLimitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Rate Limit Exceeded");
        problem.setProperty("errorCode", "AUTH_RATE_LIMIT_EXCEEDED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(problem);
    }

    @ExceptionHandler(watch.nabu.application.exception.MaliciousPayloadException.class)
    public ResponseEntity<ProblemDetail> handleMaliciousPayload(
            watch.nabu.application.exception.MaliciousPayloadException ex) {
        // Generic message intentional — do NOT leak which pattern matched
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Message blocked by security policy.");
        problem.setTitle("Content Policy Violation");
        problem.setProperty("errorCode", "MALICIOUS_PAYLOAD_BLOCKED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(PendingVerificationLimitException.class)
    public ResponseEntity<ProblemDetail> handlePendingVerificationLimit(PendingVerificationLimitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Too Many Unverified Websites");
        problem.setProperty("errorCode", "PENDING_VERIFICATION_LIMIT");
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("windowDays", ex.getWindowDays());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
    }

    @ExceptionHandler(DomainCooldownException.class)
    public ResponseEntity<ProblemDetail> handleDomainCooldown(DomainCooldownException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getHumanReadableRetry());
        problem.setTitle("Domain Cooldown Active");
        problem.setProperty("errorCode", "DOMAIN_COOLDOWN");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getSecondsRemaining()))
                .body(problem);
    }

    @ExceptionHandler(ManualRescanCooldownException.class)
    public ResponseEntity<ProblemDetail> handleManualRescanCooldown(ManualRescanCooldownException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Rescan Cooldown Active");
        problem.setProperty("errorCode", "MANUAL_RESCAN_COOLDOWN");
        problem.setProperty("minutesRemaining", ex.getMinutesRemaining());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getSecondsRemaining()))
                .body(problem);
    }

    @ExceptionHandler(UserRateLimitException.class)
    public ResponseEntity<ProblemDetail> handleUserRateLimit(UserRateLimitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Rate Limit Exceeded");
        problem.setProperty("errorCode", "RATE_LIMIT_EXCEEDED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(problem);
    }

    @ExceptionHandler(MfaChallengeRequiredException.class)
    public ProblemDetail handleMfaChallenge(MfaChallengeRequiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("MFA Step-up Required");
        problem.setProperty("errorCode", "MFA_CHALLENGE_REQUIRED");
        return problem;
    }

    @ExceptionHandler(MfaInvalidException.class)
    public ProblemDetail handleMfaInvalid(MfaInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Invalid MFA Code");
        problem.setProperty("errorCode", "MFA_INVALID");
        return problem;
    }

    @ExceptionHandler(OtpExpiredException.class)
    public ProblemDetail handleOtpExpired(OtpExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Invalid Confirmation Code");
        problem.setProperty("errorCode", "OTP_INVALID");
        return problem;
    }

    @ExceptionHandler(OtpDeliveryException.class)
    public ProblemDetail handleOtpDelivery(OtpDeliveryException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Confirmation Code Not Sent");
        problem.setProperty("errorCode", "OTP_DELIVERY_FAILED");
        return problem;
    }

    @ExceptionHandler(DomainMismatchException.class)
    public ProblemDetail handleDomainMismatch(DomainMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Domain Mismatch");
        problem.setProperty("errorCode", "DOMAIN_MISMATCH");
        return problem;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("User Not Found");
        return problem;
    }

    @ExceptionHandler(MonitorNotFoundException.class)
    public ProblemDetail handleMonitorNotFound(MonitorNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Monitor Not Found");
        return problem;
    }

    @ExceptionHandler(WebsiteNotFoundException.class)
    public ProblemDetail handleWebsiteNotFound(WebsiteNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Website Not Found");
        return problem;
    }

    @ExceptionHandler(watch.nabu.application.website.WebsiteDuplicateException.class)
    public ProblemDetail handleWebsiteDuplicate(watch.nabu.application.website.WebsiteDuplicateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Website Already Exists");
        return problem;
    }

    @ExceptionHandler(watch.nabu.application.website.WebsiteHasOpenAbuseReportException.class)
    public ProblemDetail handleWebsiteOpenAbuse(
            watch.nabu.application.website.WebsiteHasOpenAbuseReportException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Open Abuse Report");
        return problem;
    }

    /**
     * Safety net for the {@code uq_websites_user_url} database constraint.
     *
     * <p>Under normal operation the application layer detects duplicates first
     * (in {@code WebsiteService.create()}) and handles them by syncing monitors.
     * This handler fires only in the rare race condition where two concurrent
     * requests for the same URL slip past the app-level check simultaneously
     * and one of them loses the INSERT race at the DB level.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("uq_websites_user_url")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A website with this URL already exists in your account.");
            problem.setTitle("Duplicate Website");
            return problem;
        }
        // Re-throw for any other integrity violation so it is not silently swallowed.
        throw ex;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Failed");
        return problem;
    }

    /**
     * ResponseStatusException is used across services for expected, user-facing
     * failures (BAD_REQUEST etc). Spring's default mapping serialises the
     * "reason" into {@code detail} but does not log — leaving production with
     * zero visibility when a call returns 4xx.
     * Log at WARN so failure modes appear in logs without polluting on success.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        log.warn("ResponseStatusException: status={} reason={}", ex.getStatusCode(), ex.getReason());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(problem);
    }

    /**
     * A client that goes away mid-response — browser tab closed, page
     * navigated away, laptop slept, proxy timeout — makes the next write throw
     * {@link ClientAbortException} (Tomcat) or {@code AsyncRequestNotUsableException}
     * (Spring, for async/SSE responses). This is routine client churn, most
     * visibly on the long-lived {@code /api/v1/scans/stream} SSE endpoint.
     *
     * <p>Handling it explicitly matters because the catch-all below would
     * otherwise try to serialise a {@link ProblemDetail} into a response whose
     * Content-Type is already {@code text/event-stream}. That fails with
     * "No converter for ProblemDetail with preset Content-Type", so a single
     * closed browser tab produced two full stack traces at ERROR/WARN —
     * drowning real failures in noise.
     *
     * <p>The socket is gone, so there is nothing to send and nothing to fix:
     * {@code void} + no {@code @ResponseStatus} tells Spring the exception is
     * handled and no response body should be written.
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        log.debug("Client disconnected before the response completed: {}", ex.getMessage());
    }

    /**
     * Catch-all so unhandled exceptions (e.g. NPEs) leave
     * a stack trace in logs and a meaningful {@code detail} on the response
     * instead of a silent generic 500.
     *
     * <p>Spring Security exceptions ({@link AccessDeniedException},
     * {@link AuthenticationException}) are re-thrown so the security filter
     * chain can produce the correct 401/403 responses — otherwise this catch
     * would turn every denied request into a 500.
     *
     * <p>Client disconnects are also short-circuited here, not just in
     * {@link #handleClientAbort}: Spring wraps the abort in a higher-level
     * exception often enough (async dispatch, filter chain) that matching on
     * the thrown type alone misses cases. See {@link #isClientDisconnect}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before the response completed: {}", ex.getMessage());
            return null; // nothing to write — the socket is already gone
        }
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getClass().getSimpleName() + ": " + (ex.getMessage() != null ? ex.getMessage() : "Internal error"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Walks the cause chain looking for evidence that the peer hung up.
     *
     * <p>Matches by class rather than only by message where possible, but the
     * message check is kept because the same condition surfaces as a plain
     * {@link java.io.IOException} from the NIO layer on some paths.
     * {@code AsyncRequestNotUsableException} is matched by simple name to avoid
     * a hard compile-time dependency on a class that only exists from Spring
     * Framework 6.2 onward.
     */
    static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException) {
                return true;
            }
            if ("AsyncRequestNotUsableException".equals(t.getClass().getSimpleName())) {
                return true;
            }
            String msg = t.getMessage();
            if (t instanceof java.io.IOException && msg != null
                    && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer"))) {
                return true;
            }
            if (t.getCause() == t) {
                break; // defensive: self-referential cause would loop forever
            }
        }
        return false;
    }
}
