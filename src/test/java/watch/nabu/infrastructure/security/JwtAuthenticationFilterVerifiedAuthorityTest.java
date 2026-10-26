package watch.nabu.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Confirms that the authorities attached to the {@link UserDetails} returned
 * by {@link NabuUserDetailsService} propagate verbatim into the
 * {@link SecurityContextHolder} authentication set by
 * {@link JwtAuthenticationFilter}.
 *
 * <p>This is the contract that makes the email-verification wall work:
 * SecurityConfig's {@code hasAuthority("VERIFIED")} rules only reject
 * pending-verification users if the authority is missing from the
 * authenticated principal.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterVerifiedAuthorityTest {

    @Mock
    JwtService jwtService;
    @Mock
    NabuUserDetailsService userDetailsService;
    @Mock
    HttpServletRequest request;
    @Mock
    HttpServletResponse response;
    @Mock
    FilterChain chain;

    @InjectMocks
    JwtAuthenticationFilter filter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Invokes the protected {@code doFilterInternal} via reflection so the
     * test bypasses {@link org.springframework.web.filter.OncePerRequestFilter}'s
     * request bookkeeping (which would otherwise require a full servlet
     * dispatcher context).
     */
    private void invokeDoFilterInternal() throws Exception {
        var m = JwtAuthenticationFilter.class.getDeclaredMethod(
                "doFilterInternal", HttpServletRequest.class, HttpServletResponse.class, FilterChain.class);
        m.setAccessible(true);
        m.invoke(filter, request, response, chain);
    }

    @Test
    void activeUserPrincipalCarriesVerifiedAuthority() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer good-token");
        when(jwtService.extractSubject("good-token")).thenReturn("active@x.io");
        UserDetails active = User.withUsername("active@x.io")
                .password("x")
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(NabuUserDetailsService.VERIFIED_AUTHORITY)))
                .build();
        when(userDetailsService.loadUserByUsername("active@x.io")).thenReturn(active);
        when(jwtService.isValid(any(), any())).thenReturn(true);

        invokeDoFilterInternal();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_USER", NabuUserDetailsService.VERIFIED_AUTHORITY);
    }

    @Test
    void pendingUserPrincipalLacksVerifiedAuthority() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer good-token");
        when(jwtService.extractSubject("good-token")).thenReturn("pending@x.io");
        // A pending-verification principal is disabled but we still model the
        // authority set that NabuUserDetailsService would return.
        UserDetails pending = User.withUsername("pending@x.io")
                .password("x")
                .disabled(true)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        when(userDetailsService.loadUserByUsername("pending@x.io")).thenReturn(pending);
        when(jwtService.isValid(any(), any())).thenReturn(true);

        invokeDoFilterInternal();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .doesNotContain(NabuUserDetailsService.VERIFIED_AUTHORITY);
    }
}
