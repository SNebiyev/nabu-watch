package watch.nabu.application.verification;

import java.util.UUID;

/**
 * Fires whenever a user's verification status may need to change — i.e. after
 * a website is created, deleted, verified, or has its verification expired.
 *
 * <p>Listened by {@link UserVerificationService#onRecompute} via
 * {@code @TransactionalEventListener(AFTER_COMMIT)} so the recompute reads
 * the persisted post-trigger state instead of an uncommitted snapshot.
 */
public record UserVerificationRecomputeEvent(UUID userId) {
}
