package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import watch.nabu.application.exception.UserNotFoundException;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.UUID;

/**
 * Resolves the authenticated user from principal credentials.
 *
 * <p>Centralises the {@code email → User / userId / orgId} lookup that
 * previously lived in every controller as a private helper.
 */
@Service
@RequiredArgsConstructor
public class SecurityContextService {

    private final UserRepository userRepository;

    /**
     * Full entity — use when multiple fields are needed (id, email, role, orgId).
     */
    public User resolveUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));
    }

    public UUID resolveUserId(String email) {
        return resolveUser(email).getId();
    }

    public UUID resolveOrgId(String email) {
        return resolveUser(email).getCurrentOrganizationId();
    }

    /**
     * Email of the principal bound to the current SecurityContext. Returns
     * {@code null} when no authentication is present (background threads,
     * permitAll endpoints).
     */
    public static String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    /**
     * Convenience: resolves the {@link User} for the principal bound to the
     * current SecurityContext. Throws {@link UserNotFoundException} if the
     * context is empty or the user no longer exists.
     */
    public User currentUser() {
        String email = currentEmail();
        if (email == null) throw new UserNotFoundException("<anonymous>");
        return resolveUser(email);
    }
}
