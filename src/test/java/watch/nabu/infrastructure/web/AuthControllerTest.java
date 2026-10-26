package watch.nabu.infrastructure.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.security.MfaSetupService;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.security.JwtService;
import watch.nabu.infrastructure.web.dto.LoginRequest;
import watch.nabu.infrastructure.web.dto.LoginResponse;
import watch.nabu.infrastructure.web.dto.MeResponse;
import watch.nabu.infrastructure.web.dto.SwitchOrgRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    AuthenticationManager authenticationManager;
    @Mock
    JwtService jwtService;
    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserDetailsService userDetailsService;
    @Mock
    MfaSetupService mfaSetupService;
    @Mock
    OrganizationMemberRepository orgMemberRepository;
    @Mock
    OrganizationRepository orgRepository;
    @Mock
    watch.nabu.infrastructure.redis.AuthRateLimitService authRateLimitService;
    @Mock
    watch.nabu.application.onboarding.RegistrationService registrationService;

    @InjectMocks
    AuthController controller;

    private User testUser;
    private UUID userId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        testUser = User.builder()
                .email("test@example.com").fullName("Test User")
                .passwordHash("$2a$10$hash").status(User.AccountStatus.ACTIVE)
                .currentOrganizationId(orgId).build();
        ReflectionTestUtils.setField(testUser, "id", userId);
    }

    private jakarta.servlet.http.HttpServletRequest mockRequest() {
        var req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    private void stubSecurityContext(String email) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(email);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login: valid credentials returns LoginResponse with JWT")
    void login_validCredentials_returnsJwt() {
        var userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com", "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(authToken);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(jwtService.generateToken(any(UserDetails.class), eq(false), eq(orgId))).thenReturn("jwt-token");
        when(jwtService.expiryOf("jwt-token")).thenReturn(Instant.now().plusSeconds(86400));

        LoginResponse response = controller.login(new LoginRequest("test@example.com", "password"), mockRequest());

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("login: bad credentials throws LoginFailedException")
    void login_badCredentials_throws() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> controller.login(new LoginRequest("test@example.com", "wrong"), mockRequest()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("login: disabled (unverified) account throws EmailNotVerifiedException")
    void login_disabledAccount_throws() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("Disabled"));

        assertThatThrownBy(() -> controller.login(new LoginRequest("test@example.com", "pass"), mockRequest()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("login: requirePasswordChange=true is propagated to JWT")
    void login_requirePasswordChange_propagated() {
        testUser.setRequirePasswordChange(true);
        var userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com", "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        when(authenticationManager.authenticate(any())).thenReturn(authToken);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(jwtService.generateToken(any(UserDetails.class), eq(true), eq(orgId))).thenReturn("pwd-change-jwt");
        when(jwtService.expiryOf("pwd-change-jwt")).thenReturn(Instant.now().plusSeconds(86400));

        LoginResponse response = controller.login(new LoginRequest("test@example.com", "password"), mockRequest());

        assertThat(response.requirePasswordChange()).isTrue();
    }

    // ── me ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("me: returns user profile")
    void me_returnsProfile() {
        stubSecurityContext("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        when(orgMemberRepository.findAllByUserIdWithOrganization(userId)).thenReturn(List.of());

        MeResponse response = controller.me();

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.role()).isEqualTo("USER");
        assertThat(response.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("me: pending-verification user exposes emailVerified=false")
    void me_pendingUser_emailVerifiedFalse() {
        stubSecurityContext("pending@example.com");
        User pending = User.builder()
                .email("pending@example.com").fullName("Pending")
                .passwordHash("$2a$10$hash")
                .status(User.AccountStatus.PENDING_VERIFICATION)
                .currentOrganizationId(orgId).build();
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(pending));

        when(orgMemberRepository.findAllByUserIdWithOrganization(any())).thenReturn(List.of());

        MeResponse response = controller.me();

        assertThat(response.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("me: user not found throws 401")
    void me_userNotFound_throws401() {
        stubSecurityContext("unknown@example.com");
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.me())
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── switchOrg ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("switchOrg: valid org switch returns new JWT")
    void switchOrg_valid_returnsNewJwt() {
        UUID newOrgId = UUID.randomUUID();
        stubSecurityContext("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(orgMemberRepository.existsByOrganizationIdAndUserId(newOrgId, userId)).thenReturn(true);

        Organization org = Organization.builder().name("New Org").enabled(true).build();
        ReflectionTestUtils.setField(org, "id", newOrgId);
        when(orgRepository.findById(newOrgId)).thenReturn(Optional.of(org));

        var userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com", "",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);
        when(jwtService.generateToken(any(UserDetails.class), eq(newOrgId))).thenReturn("new-org-jwt");
        when(jwtService.expiryOf("new-org-jwt")).thenReturn(Instant.now().plusSeconds(86400));

        LoginResponse response = controller.switchOrg(new SwitchOrgRequest(newOrgId));

        assertThat(response.token()).isEqualTo("new-org-jwt");
        assertThat(testUser.getCurrentOrganizationId()).isEqualTo(newOrgId);
    }

    @Test
    @DisplayName("switchOrg: not a member throws 403")
    void switchOrg_notMember_throws403() {
        UUID newOrgId = UUID.randomUUID();
        stubSecurityContext("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(orgMemberRepository.existsByOrganizationIdAndUserId(newOrgId, userId)).thenReturn(false);

        assertThatThrownBy(() -> controller.switchOrg(new SwitchOrgRequest(newOrgId)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("switchOrg: disabled org throws 403")
    void switchOrg_disabledOrg_throws403() {
        UUID newOrgId = UUID.randomUUID();
        stubSecurityContext("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(orgMemberRepository.existsByOrganizationIdAndUserId(newOrgId, userId)).thenReturn(true);

        Organization disabledOrg = Organization.builder().name("Disabled Org").enabled(false).build();
        ReflectionTestUtils.setField(disabledOrg, "id", newOrgId);
        when(orgRepository.findById(newOrgId)).thenReturn(Optional.of(disabledOrg));

        assertThatThrownBy(() -> controller.switchOrg(new SwitchOrgRequest(newOrgId)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
