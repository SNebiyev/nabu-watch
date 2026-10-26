package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import watch.nabu.application.gdpr.AccountDeletionService;
import watch.nabu.application.gdpr.GdprExportService;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * GDPR data rights endpoints — export (Article 20) and deletion (Article 17).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class GdprController {

    private final GdprExportService gdprExportService;
    private final AccountDeletionService accountDeletionService;
    private final SecurityContextService securityContext;

    @GetMapping("/export")
    public Map<String, Object> exportData() {
        User user = resolveUser();
        return gdprExportService.exportUserData(user.getId(), user.getEmail());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> requestDeletion() {
        User user = resolveUser();
        accountDeletionService.requestDeletion(user.getId());

        Instant deletionDate = Instant.now().plus(30, ChronoUnit.DAYS);
        return Map.of(
                "message", "Account scheduled for deletion. You have 30 days to cancel by contacting support.",
                "deletionDate", deletionDate.toString()
        );
    }

    private User resolveUser() {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();
        return securityContext.resolveUser(email);
    }
}
