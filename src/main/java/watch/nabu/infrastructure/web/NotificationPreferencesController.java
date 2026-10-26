package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User;

/**
 * User-facing notification-preferences endpoint.
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/notification-preferences} — read current flags.</li>
 *   <li>{@code PUT /api/v1/users/me/notification-preferences} — update flags.
 *       Partial updates are supported: any {@code null} field is left unchanged.</li>
 * </ul>
 *
 * <p>Both flags control outbound email — in-app notifications (bell + WS) are
 * unaffected. Defaults (see {@link User}) are both {@code true}.
 */
@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final SecurityContextService securityContext;

    @GetMapping
    public NotificationPreferencesResponse get(@AuthenticationPrincipal UserDetails principal) {
        User user = securityContext.resolveUser(principal.getUsername());
        return new NotificationPreferencesResponse(
                user.isEmailAlertsEnabled(),
                user.isWeeklyDigestEnabled());
    }

    @PutMapping
    @Transactional
    public NotificationPreferencesResponse update(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody NotificationPreferencesRequest req) {
        User user = securityContext.resolveUser(principal.getUsername());
        if (req.emailAlertsEnabled() != null) {
            user.setEmailAlertsEnabled(req.emailAlertsEnabled());
        }
        if (req.weeklyDigestEnabled() != null) {
            user.setWeeklyDigestEnabled(req.weeklyDigestEnabled());
        }
        return new NotificationPreferencesResponse(
                user.isEmailAlertsEnabled(),
                user.isWeeklyDigestEnabled());
    }

    public record NotificationPreferencesResponse(
            boolean emailAlertsEnabled,
            boolean weeklyDigestEnabled) {
    }

    public record NotificationPreferencesRequest(
            Boolean emailAlertsEnabled,
            Boolean weeklyDigestEnabled) {
    }
}
