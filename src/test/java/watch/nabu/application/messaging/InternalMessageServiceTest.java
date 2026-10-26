package watch.nabu.application.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.MaliciousPayloadException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.domain.model.InternalMessage;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.InternalMessageRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalMessageServiceTest {

    @Mock
    InternalMessageRepository messageRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    MessageSecurityValidator securityValidator;
    @Mock
    NotificationService notificationService;
    @Mock
    SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    InternalMessageService service;

    private InternalMessage savedMessage;

    @BeforeEach
    void setUp() {
        savedMessage = InternalMessage.builder()
                .senderId("sender@example.com")
                .receiverId("receiver@example.com")
                .content("Hello!")
                .build();
        ReflectionTestUtils.setField(savedMessage, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(savedMessage, "createdAt", Instant.now());
    }

    // ── sendMessage: happy path ───────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage: persists message, pushes WS event, and sends notification")
    void sendMessage_happyPath() {
        when(messageRepository.save(any())).thenReturn(savedMessage);

        InternalMessage result = service.sendMessage("sender@example.com", "receiver@example.com", "Hello!");

        assertThat(result.getId()).isNotNull();
        verify(securityValidator).validate("Hello!");
        verify(messageRepository).save(any(InternalMessage.class));
        verify(messagingTemplate).convertAndSendToUser(
                eq("receiver@example.com"), eq("/queue/messages"), any());
        verify(notificationService).sendNewMessageNotification(
                "receiver@example.com", "sender@example.com", savedMessage.getId());
    }

    // ── sendMessage: malicious content ────────────────────────────────────────

    @Test
    @DisplayName("sendMessage: malicious content triggers security alert and rethrows")
    void sendMessage_maliciousContent_alertsAdminsAndThrows() {
        doThrow(new MaliciousPayloadException("xss")).when(securityValidator).validate(any());
        User admin = User.builder().email("admin@example.com").fullName("Admin")
                .status(User.AccountStatus.ACTIVE).build();
        ReflectionTestUtils.setField(admin, "id", UUID.randomUUID());
        when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));

        assertThatThrownBy(() ->
                service.sendMessage("evil@example.com", "victim@example.com", "<script>"))
                .isInstanceOf(MaliciousPayloadException.class);

        verify(notificationService).sendSecurityAlertToAdmins(
                eq("evil@example.com"), any(Instant.class), eq(List.of(admin)));
        verify(messageRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    // ── getInbox ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getInbox: returns paginated messages for receiver")
    void getInbox_returnsPaginatedMessages() {
        Page<InternalMessage> page = new PageImpl<>(List.of(savedMessage));
        when(messageRepository.findByReceiverIdOrderByCreatedAtDesc(any(), any())).thenReturn(page);

        Page<InternalMessage> result = service.getInbox("receiver@example.com", PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    // ── getSentMessages ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSentMessages: returns paginated messages for sender")
    void getSentMessages_returnsPaginatedMessages() {
        Page<InternalMessage> page = new PageImpl<>(List.of(savedMessage));
        when(messageRepository.findBySenderIdOrderByCreatedAtDesc(any(), any())).thenReturn(page);

        Page<InternalMessage> result = service.getSentMessages("sender@example.com", PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    // ── getUnreadDistinctSenderCount ──────────────────────────────────────────

    @Test
    @DisplayName("getUnreadDistinctSenderCount: delegates to repository")
    void getUnreadDistinctSenderCount_delegates() {
        when(messageRepository.countUnreadDistinctSenders("receiver@example.com")).thenReturn(3);

        int count = service.getUnreadDistinctSenderCount("receiver@example.com");

        assertThat(count).isEqualTo(3);
    }

    // ── markConversationAsRead ────────────────────────────────────────────────

    @Test
    @DisplayName("markConversationAsRead: delegates to repository")
    void markConversationAsRead_delegates() {
        when(messageRepository.markConversationAsRead("receiver@example.com", "sender@example.com"))
                .thenReturn(5);

        service.markConversationAsRead("receiver@example.com", "sender@example.com");

        verify(messageRepository).markConversationAsRead("receiver@example.com", "sender@example.com");
    }
}
