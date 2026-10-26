package watch.nabu.infrastructure.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.Notification;
import watch.nabu.domain.model.Notification.NotificationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    NotificationService notificationService;
    @Mock
    SecurityContextService securityContext;

    @InjectMocks
    NotificationController controller;

    private UUID userId;
    private UserDetails principal;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        when(securityContext.resolveUserId("user@example.com")).thenReturn(userId);

        principal = new org.springframework.security.core.userdetails.User(
                "user@example.com", "", List.of());
    }

    private Notification buildNotification(String title, boolean read) {
        Notification n = Notification.builder()
                .userId(userId).title(title).message("msg")
                .type(NotificationType.SCAN_COMPLETED).build();
        ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
        n.setRead(read);
        n.setCreatedAt(Instant.now());
        return n;
    }

    // ── getNotifications ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getNotifications: returns paginated response")
    void getNotifications_returnsPaginated() {
        Notification n = buildNotification("Scan done", false);
        when(notificationService.getNotificationPage(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(n)));

        var response = controller.getNotifications(principal, 0, 10);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).title()).isEqualTo("Scan done");
        assertThat(response.last()).isTrue();
    }

    @Test
    @DisplayName("getNotifications: caps page size at 50")
    void getNotifications_capsSize() {
        when(notificationService.getNotificationPage(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        controller.getNotifications(principal, 0, 999);

        verify(notificationService).getNotificationPage(eq(userId),
                argThat(p -> p.getPageSize() == 50));
    }

    // ── getUnread ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUnread: returns unread notifications")
    void getUnread_returnsUnread() {
        Notification n = buildNotification("Alert", false);
        when(notificationService.getUnreadNotifications(userId, 20)).thenReturn(List.of(n));

        var result = controller.getUnread(principal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Alert");
    }

    // ── getUnreadCount ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getUnreadCount: returns count")
    void getUnreadCount_returnsCount() {
        when(notificationService.getUnreadCount(userId)).thenReturn(5L);

        var result = controller.getUnreadCount(principal);

        assertThat(result.unread()).isEqualTo(5);
    }

    // ── markAsRead ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAsRead: returns 204 and delegates")
    void markAsRead_returns204() {
        UUID notifId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.markAsRead(notifId, principal);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(notificationService).markAsRead(notifId, userId);
    }

    // ── markAllAsRead ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAllAsRead: returns 204 and delegates")
    void markAllAsRead_returns204() {
        ResponseEntity<Void> response = controller.markAllAsRead(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(notificationService).markAllAsRead(userId);
    }
}
