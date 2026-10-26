package watch.nabu.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.messaging.InternalMessageService;
import watch.nabu.domain.model.InternalMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for the internal messaging feature.
 *
 * <pre>
 * POST /api/v1/messages                      — send a message (Zero-Trust validated)
 * GET  /api/v1/messages/inbox                — paginated inbox
 * GET  /api/v1/messages/sent                 — paginated outbox
 * GET  /api/v1/messages/unread-senders-count — distinct unread sender badge
 * PUT  /api/v1/messages/conversations/{senderEmail}/read — mark conversation read
 * </pre>
 *
 * <p>All endpoints require a valid Bearer JWT.  If the content of a send
 * request contains a malicious payload, the service throws
 * {@link watch.nabu.application.exception.MaliciousPayloadException}, which the
 * {@link GlobalExceptionHandler} maps to HTTP 400 with a generic policy message.
 */
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
public class InternalMessageController {

    private static final int MAX_PAGE_SIZE = 50;

    private final InternalMessageService messageService;

    /**
     * POST /api/v1/messages
     *
     * <p>Sends a message to another user.  The content is passed through the
     * Zero-Trust {@code MessageSecurityValidator} before any persistence occurs.
     * If a malicious payload is detected the request is rejected with HTTP 400
     * and all admins receive a real-time security alert.
     */
    @PostMapping
    public ResponseEntity<MessageDto> sendMessage(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody SendMessageRequest request) {

        InternalMessage saved = messageService.sendMessage(
                principal.getUsername(),
                request.receiverEmail(),
                request.content()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageDto.from(saved));
    }

    /**
     * GET /api/v1/messages/inbox?page=0&size=20
     *
     * <p>Returns a paginated inbox for the authenticated user, newest-first.
     */
    @GetMapping("/inbox")
    public MessagePageResponse getInbox(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<InternalMessage> result = messageService.getInbox(
                principal.getUsername(),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))
        );
        return new MessagePageResponse(
                result.getContent().stream().map(MessageDto::from).toList(),
                result.isLast()
        );
    }

    /**
     * GET /api/v1/messages/sent?page=0&size=20
     *
     * <p>Returns a paginated outbox for the authenticated user, newest-first.
     */
    @GetMapping("/sent")
    public MessagePageResponse getSentMessages(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<InternalMessage> result = messageService.getSentMessages(
                principal.getUsername(),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))
        );
        return new MessagePageResponse(
                result.getContent().stream().map(MessageDto::from).toList(),
                result.isLast()
        );
    }

    /**
     * GET /api/v1/messages/unread-senders-count
     *
     * <p>Returns the number of distinct senders who have unread messages in the
     * authenticated user's inbox.  Suitable for an inbox badge / notification dot.
     */
    @GetMapping("/unread-senders-count")
    public UnreadSendersCountDto getUnreadSendersCount(
            @AuthenticationPrincipal UserDetails principal) {

        int count = messageService.getUnreadDistinctSenderCount(principal.getUsername());
        return new UnreadSendersCountDto(count);
    }

    /**
     * PUT /api/v1/messages/conversations/{senderEmail}/read
     *
     * <p>Marks all messages from {@code senderEmail} to the authenticated user
     * as read.  Idempotent — safe to call multiple times.
     */
    @PutMapping("/conversations/{senderEmail}/read")
    public ResponseEntity<Void> markConversationAsRead(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String senderEmail) {

        messageService.markConversationAsRead(principal.getUsername(), senderEmail);
        return ResponseEntity.noContent().build();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record SendMessageRequest(
            @NotBlank @Email
            String receiverEmail,
            @NotBlank @Size(min = 1, max = 5000)
            String content
    ) {
    }

    public record MessageDto(
            UUID id,
            String senderId,
            String receiverId,
            String content,
            boolean isRead,
            @JsonFormat(shape = JsonFormat.Shape.STRING,
                    pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    timezone = "UTC")
            Instant createdAt
    ) {
        static MessageDto from(InternalMessage m) {
            return new MessageDto(
                    m.getId(),
                    m.getSenderId(),
                    m.getReceiverId(),
                    m.getContent(),
                    m.isRead(),
                    m.getCreatedAt()
            );
        }
    }

    public record MessagePageResponse(List<MessageDto> content, boolean last) {
    }

    public record UnreadSendersCountDto(int unreadSenders) {
    }
}
