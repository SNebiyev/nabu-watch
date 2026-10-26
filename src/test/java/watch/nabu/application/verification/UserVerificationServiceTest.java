package watch.nabu.application.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.VerificationStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserVerificationServiceTest {

    @Mock UserRepository userRepository;
    @Mock WebsiteRepository websiteRepository;
    @InjectMocks UserVerificationService service;

    private static Website site(Website.VerificationStatus status, Instant createdAt) {
        Website w = Website.builder().build();
        w.setVerificationStatus(status);
        ReflectionTestUtils.setField(w, "createdAt", createdAt);
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    @Test
    @DisplayName("computeStatus: no websites → UNVERIFIED")
    void noSites() {
        assertThat(UserVerificationService.computeStatus(List.of(), Instant.now()))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("computeStatus: all VERIFIED → VERIFIED")
    void allVerified() {
        Instant now = Instant.now();
        var sites = List.of(
                site(Website.VerificationStatus.VERIFIED, now.minus(30, ChronoUnit.DAYS)),
                site(Website.VerificationStatus.VERIFIED, now.minus(10, ChronoUnit.DAYS))
        );
        assertThat(UserVerificationService.computeStatus(sites, now))
                .isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("computeStatus: 1 VERIFIED + 1 PENDING within grace → VERIFIED_PENDING")
    void verifiedPlusGrace() {
        Instant now = Instant.now();
        var sites = List.of(
                site(Website.VerificationStatus.VERIFIED, now.minus(30, ChronoUnit.DAYS)),
                site(Website.VerificationStatus.PENDING, now.minus(2, ChronoUnit.DAYS))
        );
        assertThat(UserVerificationService.computeStatus(sites, now))
                .isEqualTo(VerificationStatus.VERIFIED_PENDING);
    }

    @Test
    @DisplayName("computeStatus: 1 VERIFIED + 1 PENDING past grace → UNVERIFIED")
    void verifiedPlusOldPending() {
        Instant now = Instant.now();
        var sites = List.of(
                site(Website.VerificationStatus.VERIFIED, now.minus(30, ChronoUnit.DAYS)),
                site(Website.VerificationStatus.PENDING, now.minus(20, ChronoUnit.DAYS))
        );
        assertThat(UserVerificationService.computeStatus(sites, now))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("computeStatus: VERIFIED + EXPIRED → UNVERIFIED regardless of age")
    void verifiedPlusExpired() {
        Instant now = Instant.now();
        var sites = List.of(
                site(Website.VerificationStatus.VERIFIED, now.minus(30, ChronoUnit.DAYS)),
                site(Website.VerificationStatus.EXPIRED, now.minus(1, ChronoUnit.DAYS))
        );
        assertThat(UserVerificationService.computeStatus(sites, now))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("computeStatus: only PENDING (no VERIFIED) → UNVERIFIED even within grace")
    void onlyPending() {
        Instant now = Instant.now();
        var sites = List.of(
                site(Website.VerificationStatus.PENDING, now.minus(1, ChronoUnit.DAYS))
        );
        assertThat(UserVerificationService.computeStatus(sites, now))
                .isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    @DisplayName("recomputeUserStatus persists transition VERIFIED → UNVERIFIED + revokedAt")
    void persistRevoke() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().email("u@x").fullName("U").build();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setVerificationStatus(VerificationStatus.VERIFIED);
        user.setVerifiedAt(Instant.now().minus(60, ChronoUnit.DAYS));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(websiteRepository.findAllByAddedById(userId))
                .thenReturn(List.of(
                        site(Website.VerificationStatus.EXPIRED, Instant.now().minus(30, ChronoUnit.DAYS))
                ));

        VerificationStatus next = service.recomputeUserStatus(userId);

        assertThat(next).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(user.getVerificationRevokedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("recomputeUserStatus is a no-op when status is already correct")
    void noopWhenUnchanged() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().email("u@x").fullName("U").build();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(websiteRepository.findAllByAddedById(userId))
                .thenReturn(List.of());

        VerificationStatus next = service.recomputeUserStatus(userId);

        assertThat(next).isEqualTo(VerificationStatus.UNVERIFIED);
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }
}
