package watch.nabu.application.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.VerificationStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregates per-website verification into a single user-level badge.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code UNVERIFIED} — user has no non-deleted websites, or at least one
 *       website that is not VERIFIED and is past its 7-day grace window.</li>
 *   <li>{@code VERIFIED} — user has at least one website AND every non-deleted
 *       website is VERIFIED.</li>
 *   <li>{@code VERIFIED_PENDING} — user has at least one VERIFIED website but
 *       also at least one site still inside its 7-day grace window. Preserves
 *       the badge while the user finishes setting up a freshly added site.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserVerificationService {

    /**
     * Same window the website-level verification uses. Mirrored here so the
     * "grace period for a freshly added site" matches the user's experience —
     * if their unverified site can still be verified, the badge stays.
     */
    static final Duration GRACE_WINDOW = DomainVerificationService.VERIFICATION_WINDOW;

    private final UserRepository userRepository;
    private final WebsiteRepository websiteRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Null-safe helper used by every site that mutates a website's verification
     * state — keeps the per-site recompute publishing logic in one place.
     */
    public void requestRecompute(watch.nabu.domain.model.User owner) {
        if (owner == null) return;
        eventPublisher.publishEvent(new UserVerificationRecomputeEvent(owner.getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecompute(UserVerificationRecomputeEvent event) {
        try {
            recomputeUserStatus(event.userId());
        } catch (Exception e) {
            // Listener exceptions are logged-only by Spring; make the failure visible.
            log.warn("[user-verify] recompute failed: userId={}", event.userId(), e);
        }
    }

    @Transactional
    public VerificationStatus recomputeUserStatus(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }

        List<Website> sites = websiteRepository.findAllByAddedById(userId);

        VerificationStatus next = computeStatus(sites, Instant.now());

        VerificationStatus prev = user.getVerificationStatus();
        if (prev == next) {
            return prev;
        }

        Instant now = Instant.now();
        user.setVerificationStatus(next);
        if (next == VerificationStatus.VERIFIED && user.getVerifiedAt() == null) {
            user.setVerifiedAt(now);
            user.setVerificationRevokedAt(null);
        } else if (next == VerificationStatus.UNVERIFIED && prev == VerificationStatus.VERIFIED) {
            user.setVerificationRevokedAt(now);
        }
        userRepository.save(user);
        log.info("[user-verify] userId={} prev={} next={} sites={}",
                userId, prev, next, sites.size());
        return next;
    }

    static VerificationStatus computeStatus(List<Website> sites, Instant now) {
        if (sites.isEmpty()) {
            return VerificationStatus.UNVERIFIED;
        }
        boolean anyVerified = false;
        boolean anyInGrace = false;
        boolean anyHardFail = false;
        for (Website w : sites) {
            Website.VerificationStatus ws = w.getVerificationStatus();
            if (ws == Website.VerificationStatus.VERIFIED) {
                anyVerified = true;
                continue;
            }
            // Any non-VERIFIED site contributes either a grace ticket (if young)
            // or a hard failure (if old or explicitly EXPIRED).
            boolean withinGrace = ws != Website.VerificationStatus.EXPIRED
                    && w.getCreatedAt() != null
                    && w.getCreatedAt().isAfter(now.minus(GRACE_WINDOW));
            if (withinGrace) {
                anyInGrace = true;
            } else {
                anyHardFail = true;
            }
        }
        if (!anyVerified) {
            return VerificationStatus.UNVERIFIED;
        }
        if (anyHardFail) {
            return VerificationStatus.UNVERIFIED;
        }
        return anyInGrace ? VerificationStatus.VERIFIED_PENDING : VerificationStatus.VERIFIED;
    }
}
