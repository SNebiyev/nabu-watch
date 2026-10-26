package watch.nabu.application.abuse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.domain.model.AbuseReport;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.AbuseReportRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbuseAdminServiceTest {

    @Mock AbuseReportRepository reportRepository;
    @Mock WebsiteRepository websiteRepository;
    @Mock watch.nabu.application.verification.UserVerificationService userVerificationService;
    @Mock watch.nabu.application.notification.NotificationService notificationService;
    @Mock watch.nabu.application.notification.SiteLifecycleEmailService siteLifecycleEmailService;
    @InjectMocks AbuseAdminService service;

    private static Website site() {
        Website w = Website.builder().url("https://x.test").regionCode("DE").build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setStatus(Website.WebsiteStatus.ACTIVE);
        return w;
    }

    private static AbuseReport report(Website w, AbuseReport.Status s) {
        AbuseReport r = AbuseReport.builder()
                .website(w)
                .reporterEmail("r@x")
                .message("m".repeat(25))
                .status(s)
                .proofDeadlineAt(Instant.now().plus(Duration.ofHours(48)))
                .build();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("uphold pauses site and transitions PENDING→UPHELD")
    void uphold() {
        Website w = site();
        AbuseReport r = report(w, AbuseReport.Status.PENDING);
        when(reportRepository.findByIdWithWebsite(r.getId())).thenReturn(Optional.of(r));

        AbuseReport out = service.uphold(r.getId());
        assertThat(out.getStatus()).isEqualTo(AbuseReport.Status.UPHELD);
        assertThat(w.getStatus()).isEqualTo(Website.WebsiteStatus.PAUSED);
        verify(websiteRepository).save(w);
    }

    @Test
    @DisplayName("dismiss closes the report without touching the site")
    void dismiss() {
        Website w = site();
        AbuseReport r = report(w, AbuseReport.Status.PENDING);
        when(reportRepository.findByIdWithWebsite(r.getId())).thenReturn(Optional.of(r));

        AbuseReport out = service.dismiss(r.getId());
        assertThat(out.getStatus()).isEqualTo(AbuseReport.Status.DISMISSED);
        assertThat(out.getResolvedAt()).isNotNull();
        assertThat(w.getStatus()).isEqualTo(Website.WebsiteStatus.ACTIVE);
        verify(websiteRepository, never()).save(any());
    }

    @Test
    @DisplayName("ownershipProven only allowed on UPHELD reports; reactivates site")
    void ownershipProven() {
        Website w = site();
        w.setStatus(Website.WebsiteStatus.PAUSED);
        AbuseReport r = report(w, AbuseReport.Status.UPHELD);
        when(reportRepository.findByIdWithWebsite(r.getId())).thenReturn(Optional.of(r));

        AbuseReport out = service.markOwnershipProven(r.getId());
        assertThat(out.getStatus()).isEqualTo(AbuseReport.Status.OWNERSHIP_PROVEN);
        assertThat(w.getStatus()).isEqualTo(Website.WebsiteStatus.ACTIVE);
    }

    @Test
    @DisplayName("ownershipProven on non-UPHELD report returns 409")
    void ownershipProvenRejectsNonUpheld() {
        Website w = site();
        AbuseReport r = report(w, AbuseReport.Status.PENDING);
        when(reportRepository.findByIdWithWebsite(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.markOwnershipProven(r.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expected UPHELD");
    }

    @Test
    @DisplayName("sweepExpired hard-deletes the website and reports the count")
    void sweepExpired() {
        Website w = site();
        w.setStatus(Website.WebsiteStatus.PAUSED);
        AbuseReport r = report(w, AbuseReport.Status.UPHELD);
        r.setProofDeadlineAt(Instant.now().minus(Duration.ofHours(1)));

        when(reportRepository.findAllByStatusAndProofDeadlineAtBefore(
                any(), any())).thenReturn(List.of(r));

        int removed = service.sweepExpired();
        assertThat(removed).isEqualTo(1);
        verify(websiteRepository).delete(w);
        verify(userVerificationService).requestRecompute(w.getAddedBy());
        verify(siteLifecycleEmailService).sendAbuseSiteRemoved(w.getAddedBy(), w);
    }

    @Test
    @DisplayName("sweepExpired with orphan report (null website) marks SITE_REMOVED in place")
    void sweepExpiredOrphan() {
        AbuseReport r = AbuseReport.builder()
                .reporterEmail("r@x")
                .message("m".repeat(25))
                .status(AbuseReport.Status.UPHELD)
                .proofDeadlineAt(Instant.now().minus(Duration.ofHours(1)))
                .build();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());

        when(reportRepository.findAllByStatusAndProofDeadlineAtBefore(
                any(), any())).thenReturn(List.of(r));

        int removed = service.sweepExpired();
        assertThat(removed).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(AbuseReport.Status.SITE_REMOVED);
        assertThat(r.getResolvedAt()).isNotNull();
        verify(websiteRepository, never()).delete(any(Website.class));
    }

    @Test
    @DisplayName("uphold on non-PENDING report returns 409")
    void upholdRejectsNonPending() {
        Website w = site();
        AbuseReport r = report(w, AbuseReport.Status.DISMISSED);
        when(reportRepository.findByIdWithWebsite(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.uphold(r.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expected PENDING");
    }
}
