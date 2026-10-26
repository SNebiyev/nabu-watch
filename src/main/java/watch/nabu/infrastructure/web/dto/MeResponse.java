package watch.nabu.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/auth/me}.
 *
 * <p>Bundles the current user's identity, organization memberships and
 * verification status into one round-trip so the frontend can
 * paint the dashboard header without additional API calls.
 */
public record MeResponse(
        UUID id,
        String email,
        String fullName,
        String role,
        /** True until the user adds their first website (drives the onboarding wizard). */
        boolean firstLogin,
        /**
         * {@code true} once the user has clicked the email verification link
         * ({@code AccountStatus.ACTIVE}).  The frontend uses this to render the
         * persistent "please verify your email" banner and to gate navigation
         * to protected routes before the backend 403s them.
         */
        boolean emailVerified,
        UUID currentOrganizationId,
        List<OrgInfo> organizations,
        /**
         * Aggregate trust badge for the user, recomputed from their owned
         * websites. Values mirror {@code User.VerificationStatus}:
         * {@code UNVERIFIED}, {@code VERIFIED}, {@code VERIFIED_PENDING}.
         */
        String verificationStatus
) {

    /**
     * Organization membership summary for the org switcher.
     */
    public record OrgInfo(
            UUID id,
            String name,
            String role
    ) {
    }
}
