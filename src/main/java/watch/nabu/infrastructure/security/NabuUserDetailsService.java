package watch.nabu.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@link User} entities from the database for Spring Security authentication.
 *
 * <p>Account liveness rules:
 * <ul>
 *   <li>Only {@code ACTIVE} accounts can authenticate.</li>
 *   <li>{@code SUSPENDED} / {@code DELETED} accounts are locked.</li>
 *   <li>{@code PENDING_VERIFICATION} accounts are non-enabled (can be unlocked
 *       later once email verification is introduced).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NabuUserDetailsService implements UserDetailsService {

    /**
     * Authority granted only to users whose email has been verified
     * (i.e. {@code AccountStatus.ACTIVE}).  The email-verification wall in
     * {@link SecurityConfig} requires this authority on every mutating /
     * non-trivial API route — PENDING_VERIFICATION principals may still hit
     * {@code /api/v1/auth/**} (resend verification, logout) and
     * {@code GET /api/v1/auth/me} (so the UI can render the "verify your
     * email" screen) but are 403'd everywhere else.
     */
    public static final String VERIFIED_AUTHORITY = "VERIFIED";

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("No user found with email: " + email));

        boolean active  = user.getStatus() == User.AccountStatus.ACTIVE;
        boolean locked  = user.getStatus() == User.AccountStatus.SUSPENDED
                       || user.getStatus() == User.AccountStatus.DELETED;

        // OAuth users have no passwordHash — use empty string so Spring Security's
        // User builder doesn't throw; DaoAuthenticationProvider will reject it anyway
        // since BCrypt never produces an empty-string hash.
        String password = user.getPasswordHash() != null ? user.getPasswordHash() : "";

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (active) {
            authorities.add(new SimpleGrantedAuthority(VERIFIED_AUTHORITY));
        }

        // PENDING_VERIFICATION remains `disabled` so password login still throws
        // DisabledException; the JWT filter still authenticates them (with no
        // VERIFIED authority) so the UI can render the "verify your email" screen.
        return org.springframework.security.core.userdetails.User.builder()
            .username(user.getEmail())
                .password(password)
                .authorities(authorities)
            .accountLocked(locked)
                .disabled(!active && !locked)
            .build();
    }
}
