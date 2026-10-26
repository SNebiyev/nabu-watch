package watch.nabu.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.Optional;

/** Owner-side controls for the {@code /u/{username}} public trust profile. */
@RestController
@RequestMapping("/api/v1/users/me/public-profile")
@RequiredArgsConstructor
public class PublicProfileSettingsController {

    private static final java.util.regex.Pattern SLUG_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$");

    private final SecurityContextService securityContext;
    private final UserRepository userRepository;

    @GetMapping
    public PublicProfileResponse get(@AuthenticationPrincipal UserDetails principal) {
        return toResponse(securityContext.resolveUser(principal.getUsername()));
    }

    @PutMapping
    @Transactional
    public PublicProfileResponse update(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody PublicProfileRequest req) {
        try {
            return applyUpdate(principal, req);
        } catch (DataIntegrityViolationException e) {
            // Concurrent PUTs racing on the same slug: the expression index
            // catches the dup even when the pre-check above did not. Map to
            // the same 409 the pre-check would have produced.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
    }

    private PublicProfileResponse applyUpdate(UserDetails principal, PublicProfileRequest req) {
        User user = securityContext.resolveUser(principal.getUsername());

        if (req.publicUsername() != null) {
            String slug = req.publicUsername().trim().toLowerCase();
            if (slug.isEmpty()) {
                user.setPublicUsername(null);
                user.setPublicProfileEnabled(false);
            } else {
                if (!SLUG_PATTERN.matcher(slug).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Username must be 3–40 chars, lowercase a–z/0–9/-, starting and ending with a letter or digit");
                }
                userRepository.findByPublicUsernameIgnoreCase(slug)
                        .filter(u -> !u.getId().equals(user.getId()))
                        .ifPresent(u -> {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
                        });
                user.setPublicUsername(slug);
            }
        }

        if (req.publicProfileEnabled() != null) {
            if (req.publicProfileEnabled() && (user.getPublicUsername() == null || user.getPublicUsername().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Pick a public username before enabling the profile");
            }
            user.setPublicProfileEnabled(req.publicProfileEnabled());
        }

        // Force the flush inside this try-block so a concurrent-PUT index
        // violation surfaces as DataIntegrityViolationException here instead
        // of a 500 at transaction-commit time.
        userRepository.saveAndFlush(user);
        return toResponse(user);
    }

    private static PublicProfileResponse toResponse(User user) {
        return new PublicProfileResponse(
                user.getPublicUsername(),
                user.isPublicProfileEnabled(),
                user.getVerificationStatus(),
                user.getVerifiedAt(),
                user.getVerificationRevokedAt());
    }

    public record PublicProfileRequest(
            @Size(max = 40) String publicUsername,
            Boolean publicProfileEnabled
    ) {}

    public record PublicProfileResponse(
            String publicUsername,
            boolean publicProfileEnabled,
            User.VerificationStatus verificationStatus,
            Instant verifiedAt,
            Instant verificationRevokedAt
    ) {}
}
