package watch.nabu.infrastructure.web.dto;

import watch.nabu.domain.model.WebsiteVerification;

import java.time.Instant;

/**
 * Verify-domain card payload: everything the UI needs to render the TXT
 * challenge, the countdown, and the last-check outcome.
 */
public record WebsiteVerificationResponse(
        String status,
        /** Fully-qualified name the user must place the TXT on (e.g. {@code _nabu-challenge.example.com}). */
        String recordName,
        /** The value the TXT record must contain (e.g. {@code nabu-verify=ABC123…}). */
        String recordValue,
        Instant createdAt,
        Instant verifiedAt,
        Instant expiresAt,
        Instant lastCheckedAt,
        int checkCount,
        String failureReason
) {
    public static WebsiteVerificationResponse from(WebsiteVerification v, String host) {
        return new WebsiteVerificationResponse(
                v.getStatus().name(),
                "_nabu-challenge." + host,
                v.getToken(),
                v.getCreatedAt(),
                v.getVerifiedAt(),
                v.getExpiresAt(),
                v.getLastCheckedAt(),
                v.getCheckCount(),
                v.getFailureReason()
        );
    }
}
