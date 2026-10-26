package watch.nabu.application.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.Website;
import watch.nabu.domain.model.WebsiteVerification;
import watch.nabu.domain.model.WebsiteVerification.Status;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.persistence.WebsiteVerificationRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DomainVerificationServiceTest {

    @Mock WebsiteVerificationRepository verificationRepository;
    @Mock WebsiteRepository websiteRepository;
    @Mock ObjectProvider<UserVerificationService> userVerificationProvider;
    @Mock UserVerificationService userVerificationService;
    @Mock watch.nabu.application.notification.NotificationService notificationService;
    @Mock watch.nabu.application.notification.SiteLifecycleEmailService siteLifecycleEmailService;

    /** Spy so we can stub out the real DNS call — DNS is not reachable in unit tests. */
    @Spy @InjectMocks DomainVerificationService service;

    @org.junit.jupiter.api.BeforeEach
    void wireProvider() {
        org.mockito.Mockito.lenient()
                .when(userVerificationProvider.getObject())
                .thenReturn(userVerificationService);
    }

    private Website website(String url) {
        Website w = Website.builder()
                .organization(Organization.builder().build())
                .url(url)
                .regionCode("DE")
                .build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    @Test
    @DisplayName("generateToken produces nabu-verify= prefix + 32 base32 chars")
    void tokenFormat() {
        String t = DomainVerificationService.generateToken();
        assertThat(t).startsWith("nabu-verify=");
        assertThat(t.substring("nabu-verify=".length())).hasSize(32)
                .matches("[A-Z2-7]+");
    }

    @Test
    @DisplayName("extractHost strips leading www. and tolerates malformed URLs")
    void extractHost() {
        assertThat(DomainVerificationService.extractHost("https://example.com/foo")).isEqualTo("example.com");
        assertThat(DomainVerificationService.extractHost("https://www.example.com")).isEqualTo("example.com");
        assertThat(DomainVerificationService.extractHost("not-a-url")).isNull();
        assertThat(DomainVerificationService.extractHost(null)).isNull();
    }

    @Test
    @DisplayName("provision creates PENDING row with 7-day expiry and mirrors status onto website")
    void provisionHappyPath() {
        Website w = website("https://example.com");

        WebsiteVerification result = service.provision(w);

        assertThat(result.getStatus()).isEqualTo(Status.PENDING);
        assertThat(result.getToken()).startsWith("nabu-verify=");
        assertThat(result.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 86400));
        assertThat(w.getVerificationStatus()).isEqualTo(Website.VerificationStatus.PENDING);
        verify(verificationRepository).save(result);
    }

    @Test
    @DisplayName("check: matching TXT record transitions PENDING → VERIFIED and mirrors onto website")
    void checkVerifiedPath() {
        Website w = website("https://example.com");
        WebsiteVerification v = WebsiteVerification.builder()
                .website(w)
                .token("nabu-verify=ABCDE")
                .status(Status.PENDING)
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(verificationRepository.findByWebsiteId(w.getId())).thenReturn(Optional.of(v));
        // Stub DNS: TXT record matches exactly
        doReturn(DomainVerificationService.LookupResult.success(java.util.List.of("nabu-verify=ABCDE")))
                .when(service).lookupTxt("_nabu-challenge.example.com");

        WebsiteVerification result = service.check(w.getId());

        assertThat(result.getStatus()).isEqualTo(Status.VERIFIED);
        assertThat(v.getStatus()).isEqualTo(Status.VERIFIED);
        assertThat(v.getVerifiedAt()).isNotNull();
        assertThat(v.getFailureReason()).isNull();
        assertThat(w.getVerificationStatus()).isEqualTo(Website.VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("check: TXT missing → stays PENDING, increments counter, records reason")
    void checkNxDomain() {
        Website w = website("https://example.com");
        WebsiteVerification v = WebsiteVerification.builder()
                .website(w)
                .token("nabu-verify=XYZ")
                .status(Status.PENDING)
                .checkCount(2)
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(verificationRepository.findByWebsiteId(w.getId())).thenReturn(Optional.of(v));
        doReturn(DomainVerificationService.LookupResult.failure("NameNotFoundException: NXDOMAIN"))
                .when(service).lookupTxt(any());

        WebsiteVerification result = service.check(w.getId());

        assertThat(result.getStatus()).isNotEqualTo(Status.VERIFIED);
        assertThat(v.getStatus()).isEqualTo(Status.PENDING);
        assertThat(v.getCheckCount()).isEqualTo(3);
        assertThat(v.getFailureReason()).contains("NXDOMAIN");
        assertThat(w.getVerificationStatus()).isNotEqualTo(Website.VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("check: VERIFIED status is terminal — no DNS call, no regression")
    void checkTerminalWhenVerified() {
        Website w = website("https://example.com");
        w.setVerificationStatus(Website.VerificationStatus.VERIFIED);
        WebsiteVerification v = WebsiteVerification.builder()
                .website(w)
                .token("nabu-verify=OK")
                .status(Status.VERIFIED)
                .verifiedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(verificationRepository.findByWebsiteId(w.getId())).thenReturn(Optional.of(v));

        WebsiteVerification result = service.check(w.getId());

        // Status stays VERIFIED (terminal state) — unchanged, not touched.
        assertThat(result.getStatus()).isEqualTo(Status.VERIFIED);
        assertThat(result.getCheckCount()).isZero();
        verify(service, never()).lookupTxt(any());
    }

    @Test
    @DisplayName("check: present TXT with wrong value → failure reason, no state change")
    void checkMismatch() {
        Website w = website("https://example.com");
        WebsiteVerification v = WebsiteVerification.builder()
                .website(w)
                .token("nabu-verify=EXPECTED")
                .status(Status.PENDING)
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        when(verificationRepository.findByWebsiteId(w.getId())).thenReturn(Optional.of(v));
        doReturn(DomainVerificationService.LookupResult.success(java.util.List.of("nabu-verify=WRONG")))
                .when(service).lookupTxt(any());

        WebsiteVerification result = service.check(w.getId());

        assertThat(result.getStatus()).isNotEqualTo(Status.VERIFIED);
        assertThat(v.getStatus()).isEqualTo(Status.PENDING);
        assertThat(v.getFailureReason()).contains("does not match");
    }

    @Test
    @DisplayName("sweepExpired transitions past-due PENDING rows to EXPIRED")
    void sweepExpired() {
        Website w = website("https://example.com");
        WebsiteVerification v = WebsiteVerification.builder()
                .website(w)
                .token("nabu-verify=OLD")
                .status(Status.PENDING)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(verificationRepository.findExpired(any())).thenReturn(java.util.List.of(v));

        int count = service.sweepExpired();

        assertThat(count).isEqualTo(1);
        assertThat(v.getStatus()).isEqualTo(Status.EXPIRED);
        assertThat(w.getVerificationStatus()).isEqualTo(Website.VerificationStatus.EXPIRED);
    }
}
