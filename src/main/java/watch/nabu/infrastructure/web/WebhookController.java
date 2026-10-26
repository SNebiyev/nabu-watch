package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.application.webhook.WebhookService;
import watch.nabu.domain.model.WebhookConfig;
import watch.nabu.domain.model.WebhookConfig.WebhookType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;
    private final SecurityContextService securityContext;

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<WebhookConfigResponse> list(@AuthenticationPrincipal UserDetails principal) {
        UUID orgId = resolveOrgId(principal);
        return webhookService.listForOrg(orgId).stream()
                .map(WebhookConfigResponse::from)
                .toList();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookConfigResponse create(
            @RequestBody @Valid CreateWebhookRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID orgId = resolveOrgId(principal);
        WebhookConfig config = webhookService.create(
                orgId, request.name(), request.url(),
                request.secret(), request.type());
        return WebhookConfigResponse.from(config);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public WebhookConfigResponse update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWebhookRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID orgId = resolveOrgId(principal);
        WebhookConfig config = webhookService.update(
                id, orgId, request.name(), request.url(),
                request.secret(), request.type(), request.enabled());
        return WebhookConfigResponse.from(config);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        UUID orgId = resolveOrgId(principal);
        webhookService.delete(id, orgId);
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void test(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        UUID orgId = resolveOrgId(principal);
        webhookService.sendTest(id, orgId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID resolveOrgId(UserDetails principal) {
        return securityContext.resolveOrgId(principal.getUsername());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CreateWebhookRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 255) String secret,
            WebhookType type
    ) {
    }

    public record UpdateWebhookRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 255) String secret,
            WebhookType type,
            boolean enabled
    ) {
    }

    public record WebhookConfigResponse(
            UUID id,
            String name,
            String url,
            boolean hasSecret,
            WebhookType type,
            boolean enabled,
            Instant createdAt
    ) {
        static WebhookConfigResponse from(WebhookConfig c) {
            return new WebhookConfigResponse(
                    c.getId(), c.getName(), c.getUrl(),
                    c.getSecret() != null && !c.getSecret().isBlank(),
                    c.getType(), c.isEnabled(), c.getCreatedAt());
        }
    }
}
