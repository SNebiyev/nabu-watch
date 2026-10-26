package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.Notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the notification bell.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/notifications/unread}        — initial load (up to 20)</li>
 *   <li>{@code GET  /api/v1/notifications/unread-count}  — badge count</li>
 *   <li>{@code PUT  /api/v1/notifications/{id}/read}     — mark one as read</li>
 *   <li>{@code PUT  /api/v1/notifications/read-all}      — mark all as read</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final int MAX_UNREAD = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationService notificationService;
    private final SecurityContextService securityContext;

    /**
     * GET /api/v1/notifications?page=0&size=10
     * <p>
     * Returns a paginated slice of all notifications for the authenticated user,
     * newest-first.  Page size is capped at {@value MAX_PAGE_SIZE} to prevent
     * over-fetching regardless of the {@code size} parameter supplied by the client.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "content": [ { id, title, message, type, relatedEntityId, read, createdAt }, … ],
     *   "last": true          // false when more pages exist → show "Load more"
     * }
     * </pre>
     */
    @GetMapping
    public NotificationPageResponse getNotifications(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID userId = resolveUserId(principal);
        Page<Notification> result = notificationService.getNotificationPage(
                userId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return new NotificationPageResponse(
                result.getContent().stream().map(NotificationDto::from).toList(),
                result.isLast()
        );
    }

    /**
     * Returns the most-recent unread notifications (up to 20).
     */
    @GetMapping("/unread")
    public List<NotificationDto> getUnread(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = resolveUserId(principal);
        return notificationService.getUnreadNotifications(userId, MAX_UNREAD)
                .stream()
                .map(NotificationDto::from)
                .toList();
    }

    /**
     * Returns only the unread count — cheap query for polling or SSE-less refresh.
     */
    @GetMapping("/unread-count")
    public CountDto getUnreadCount(@AuthenticationPrincipal UserDetails principal) {
        UUID userId = resolveUserId(principal);
        return new CountDto(notificationService.getUnreadCount(userId));
    }

    /**
     * Marks one notification as read. Idempotent — silently ignores already-read IDs.
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = resolveUserId(principal);
        notificationService.markAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Marks every unread notification for this user as read.
     */
    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = resolveUserId(principal);
        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID resolveUserId(UserDetails principal) {
        return securityContext.resolveUserId(principal.getUsername());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record NotificationDto(
            UUID id,
            String title,
            String message,
            String type,
            UUID relatedEntityId,
            boolean read,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
            Instant createdAt,
            String organizationName
    ) {
        static NotificationDto from(Notification n) {
            return new NotificationDto(
                    n.getId(),
                    n.getTitle(),
                    n.getMessage(),
                    n.getType().name(),
                    n.getRelatedEntityId(),
                    n.isRead(),
                    n.getCreatedAt(),
                    n.getOrganizationName()
            );
        }
    }

    public record CountDto(long unread) {
    }

    /**
     * Paginated response for {@code GET /api/v1/notifications}.
     */
    public record NotificationPageResponse(
            List<NotificationDto> content,
            /** {@code true} when no further pages exist — hides the "Load more" button. */
            boolean last
    ) {
    }
}
