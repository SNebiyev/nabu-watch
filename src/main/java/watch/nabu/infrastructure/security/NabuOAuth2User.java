package watch.nabu.infrastructure.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin decorator around an {@link OAuth2User} that replaces the {@code email}
 * attribute with the canonical, provider-verified address resolved during login.
 *
 * <h3>Why this exists</h3>
 * GitHub's {@code /user} endpoint may return {@code null} or a noreply address
 * ({@code 12345+login@users.noreply.github.com}) for users who have set their
 * email to private.  The real email is fetched separately from
 * {@code GET /user/emails}, but the resolved value must be visible to every
 * component that reads the security principal — in particular
 * {@link OAuth2AuthenticationSuccessHandler}, which extracts the email from
 * {@code getAttribute("email")} to look up the {@code UserDetails} and issue
 * the Nabu JWT.
 *
 * <h3>What it overrides</h3>
 * Only {@link #getAttributes()} is customised; all other methods — including
 * {@link #getName()} (used by Spring Security as the principal name) and
 * {@link #getAuthorities()} — are delegated unchanged to the wrapped user.
 */
final class NabuOAuth2User implements OAuth2User {

    private final OAuth2User delegate;
    private final Map<String, Object> attributes;

    NabuOAuth2User(OAuth2User delegate, String resolvedEmail) {
        this.delegate = delegate;
        // The map returned by DefaultOAuth2UserService is unmodifiable, so we copy it.
        Map<String, Object> copy = new LinkedHashMap<>(delegate.getAttributes());
        copy.put("email", resolvedEmail.toLowerCase());
        this.attributes = Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    /**
     * Delegates to the underlying principal — preserves the provider's user-name key.
     */
    @Override
    public String getName() {
        return delegate.getName();
    }
}
