package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.VerificationStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.persistence.WebsiteRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Public read-only profile for verified users — backs {@code /u/{username}}
 * trust pages on the marketing site. Listed in {@code SecurityConfig} as
 * {@code permitAll} so unauthenticated visitors can fetch it.
 */
@RestController
@RequestMapping("/api/v1/public/users")
@RequiredArgsConstructor
public class PublicUserController {

    private final UserRepository userRepository;
    private final WebsiteRepository websiteRepository;

    @GetMapping("/{username}")
    public ResponseEntity<PublicUserProfileResponse> getProfile(@PathVariable String username) {
        Optional<User> userOpt = userRepository
                .findByPublicUsernameIgnoreCaseAndPublicProfileEnabled(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = userOpt.get();

        List<PublicWebsiteEntry> sites = websiteRepository
                .findAllByAddedByIdAndVerificationStatus(
                        user.getId(),
                        Website.VerificationStatus.VERIFIED)
                .stream()
                .map(w -> new PublicWebsiteEntry(
                        w.getUrl(),
                        w.getRegionCode(),
                        w.getComplianceGrade(),
                        w.getVerifiedAt()))
                .toList();

        PublicUserProfileResponse body = new PublicUserProfileResponse(
                user.getPublicUsername(),
                user.getFullName(),
                user.getVerificationStatus(),
                user.getVerifiedAt(),
                sites);
        return ResponseEntity.ok(body);
    }

    public record PublicUserProfileResponse(
            String username,
            String displayName,
            VerificationStatus verificationStatus,
            Instant verifiedAt,
            List<PublicWebsiteEntry> websites
    ) {}

    public record PublicWebsiteEntry(
            String url,
            String regionCode,
            String complianceGrade,
            Instant verifiedAt
    ) {}
}
