package watch.nabu.application.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteLifecycleEmailServiceTest {

    @Mock MessageDispatcher messageDispatcher;
    @Mock UnsubscribeTokenService unsubscribeTokenService;
    @InjectMocks SiteLifecycleEmailService service;

    private User user;
    private Website site;

    @BeforeEach
    void setup() {
        user = User.builder().email("owner@example.com").fullName("Owner").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmailAlertsEnabled(true);

        site = Website.builder().url("https://example.com").regionCode("DE").build();
        ReflectionTestUtils.setField(site, "id", UUID.randomUUID());

        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:3000");
        org.mockito.Mockito.lenient()
                .when(unsubscribeTokenService.buildUnsubscribeUrl(any(), any()))
                .thenReturn("http://localhost:3000/unsubscribe?token=abc");
    }

    @Test
    @DisplayName("sendAbuseUpheld dispatches an email with the 48h warning")
    void upheld() {
        service.sendAbuseUpheld(user, site);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher).dispatch(captor.capture());
        Message msg = captor.getValue();
        assertThat(msg.channel()).isEqualTo(Message.Channel.EMAIL);
        assertThat(msg.recipient()).isEqualTo("owner@example.com");
        assertThat(msg.subject()).contains("paused");
        assertThat(msg.textBody()).contains("48 hours");
        assertThat(msg.htmlBody()).contains("https://example.com");
        assertThat(msg.htmlBody()).contains("unsubscribe?token=abc");
    }

    @Test
    @DisplayName("Opt-out (lifecycleEmailsEnabled=false) suppresses delivery")
    void optOut() {
        user.setLifecycleEmailsEnabled(false);
        service.sendAbuseUpheld(user, site);
        service.sendAbuseOwnershipRestored(user, site);
        service.sendAbuseSiteRemoved(user, site);
        service.sendVerificationExpired(user, site);
        service.sendVerificationGraceWarning(user, site);
        verify(messageDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("Scan-alerts opt-out does NOT silence lifecycle emails (flags are independent)")
    void scanAlertsOptOutDoesNotTouchLifecycle() {
        user.setEmailAlertsEnabled(false);
        user.setLifecycleEmailsEnabled(true);
        service.sendAbuseUpheld(user, site);
        verify(messageDispatcher, org.mockito.Mockito.times(1)).dispatch(any());
    }

    @Test
    @DisplayName("sendVerificationGraceWarning dispatches a distinct 24h-before-expiry email")
    void graceWarning() {
        service.sendVerificationGraceWarning(user, site);
        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher).dispatch(captor.capture());
        Message m = captor.getValue();
        assertThat(m.subject()).contains("expires soon");
        assertThat(m.textBody()).contains("less than 24 hours");
    }

    @Test
    @DisplayName("Null owner is a no-op (orphaned website safety)")
    void nullOwner() {
        service.sendAbuseUpheld(null, site);
        verify(messageDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("Dispatch failure is swallowed (email must never break the triggering flow)")
    void swallowsFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(messageDispatcher).dispatch(any());
        // Should not throw
        service.sendAbuseSiteRemoved(user, site);
        verify(messageDispatcher).dispatch(any());
    }

    @Test
    @DisplayName("Each method produces its own subject so recipients can filter")
    void distinctSubjects() {
        service.sendAbuseUpheld(user, site);
        service.sendAbuseOwnershipRestored(user, site);
        service.sendAbuseSiteRemoved(user, site);
        service.sendVerificationExpired(user, site);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDispatcher, org.mockito.Mockito.times(4)).dispatch(captor.capture());
        var subjects = captor.getAllValues().stream().map(Message::subject).toList();
        assertThat(subjects).containsExactly(
                "Website paused — abuse report upheld",
                "Website reactivated",
                "Website removed after abuse report",
                "Domain verification expired for https://example.com");
    }
}
