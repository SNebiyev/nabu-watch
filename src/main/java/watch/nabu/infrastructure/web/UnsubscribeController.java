package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.notification.UnsubscribeTokenService;
import watch.nabu.application.notification.UnsubscribeTokenService.ParsedToken;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

/**
 * One-click email unsubscribe endpoint.
 *
 * <p>Public (no JWT required) — the HMAC token in the query parameter is the
 * sole proof of authorisation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/unsubscribe")
@RequiredArgsConstructor
public class UnsubscribeController {

    private final UnsubscribeTokenService tokenService;
    private final UserRepository userRepository;

    private static String labelFor(String type) {
        return switch (type) {
            case "alerts" -> "scan alert emails";
            case "digest" -> "weekly digest emails";
            case "lifecycle" -> "website lifecycle emails";
            default -> "emails";
        };
    }

    @PostMapping
    @Transactional
    public UnsubscribeResponse unsubscribe(@RequestParam String token) {
        ParsedToken parsed = requireValidToken(token);

        User user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        switch (parsed.type()) {
            case "alerts" -> user.setEmailAlertsEnabled(false);
            case "digest" -> user.setWeeklyDigestEnabled(false);
            case "lifecycle" -> user.setLifecycleEmailsEnabled(false);
        }

        log.info("[unsubscribe] userId={} type={} disabled", user.getId(), parsed.type());
        return new UnsubscribeResponse(true, "You have been unsubscribed from " + labelFor(parsed.type()) + ".");
    }

    @GetMapping
    public UnsubscribeInfoResponse info(@RequestParam String token) {
        ParsedToken parsed = requireValidToken(token);
        return new UnsubscribeInfoResponse(parsed.type(), labelFor(parsed.type()));
    }

    private ParsedToken requireValidToken(String token) {
        ParsedToken parsed = tokenService.validate(token);
        if (parsed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired unsubscribe token");
        }
        return parsed;
    }

    public record UnsubscribeResponse(boolean success, String message) {
    }

    public record UnsubscribeInfoResponse(String type, String label) {
    }
}
