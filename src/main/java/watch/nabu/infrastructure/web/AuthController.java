package watch.nabu.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.onboarding.RegistrationService;
import watch.nabu.application.security.MfaSetupService;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.redis.AuthRateLimitService;
import watch.nabu.infrastructure.security.JwtService;
import watch.nabu.infrastructure.web.dto.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication endpoints — all routes under {@code /api/v1/auth} are public.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsService userDetailsService;
    private final MfaSetupService mfaSetupService;
    private final OrganizationMemberRepository orgMemberRepository;
    private final OrganizationRepository orgRepository;
    private final RegistrationService registrationService;
    private final AuthRateLimitService authRateLimitService;

    /**
     * POST /api/v1/auth/login
     *
     * Authenticates the user with email + password and returns a signed JWT.
     * The token must be included in subsequent requests as:
     * {@code Authorization: Bearer <token>}
     *
     * <p>Error responses follow RFC 9457 ProblemDetail:
     * <ul>
     *   <li>{@code 401} — wrong credentials or account not active</li>
     *   <li>{@code 400} — validation failure (blank fields, invalid email)</li>
     * </ul>
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest) {
        authRateLimitService.checkLoginRate(HttpRequestUtils.clientIp(httpRequest));
        authRateLimitService.checkAccountLock(request.email());

        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );

            // Load user entity to include userId and role in the response
            User user = userRepository.findByEmail(request.email())
                    .orElseThrow(); // safe — authentication already succeeded

            // Clear failed login counter on success
            authRateLimitService.clearFailedAttempts(request.email());

            var userDetails =
                    (org.springframework.security.core.userdetails.UserDetails) auth.getPrincipal();
            String jwt = jwtService.generateToken(userDetails, user.isRequirePasswordChange(),
                    user.getCurrentOrganizationId());

            log.info("Login successful: userId={} email={} orgId={} requirePasswordChange={}",
                    user.getId(), user.getEmail(), user.getCurrentOrganizationId(),
                    user.isRequirePasswordChange());

            return new LoginResponse(
                    jwt,
                    "Bearer",
                    jwtService.expiryOf(jwt),
                    user.getId(),
                    user.getRole().name(),
                    user.isRequirePasswordChange()
            );
        } catch (DisabledException e) {
            // PENDING_VERIFICATION user — guide them to verify, not a login failure
            throw new EmailNotVerifiedException();
        } catch (AuthenticationException e) {
            authRateLimitService.recordFailedLogin(request.email());
            throw new LoginFailedException();
        }
    }

    /**
     * POST /api/v1/auth/change-password
     *
     * Verifies the caller's current password then replaces it with the new one.
     * Clears the {@code requirePasswordChange} flag and issues a fresh JWT
     * without the {@code pwdChangeRequired} claim.
     *
     * <p>Requires a valid Bearer token (the endpoint is NOT in the public permit-list).
     */
    @PostMapping("/change-password")
    public LoginResponse changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setRequirePasswordChange(false);
        userRepository.save(user);

        log.info("Password changed: userId={} email={}", user.getId(), user.getEmail());

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        String jwt = jwtService.generateToken(userDetails, false, user.getCurrentOrganizationId());

        return new LoginResponse(
            jwt,
            "Bearer",
            jwtService.expiryOf(jwt),
            user.getId(),
            user.getRole().name(),
            false
        );
    }

    // ── MFA setup endpoints ───────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/mfa/setup
     * <p>
     * Generates a new TOTP secret for the authenticated user and returns the
     * Base32 secret + an {@code otpauth://} QR URI to display in the UI.
     * TOTP is NOT yet active — the user must call {@code /mfa/confirm} with
     * a live 6-digit code to activate it.
     *
     * <p>Requires a valid Bearer token.
     */
    @PostMapping("/mfa/setup")
    public MfaSetupResponse mfaSetup() {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();
        return mfaSetupService.setup(email);
    }

    /**
     * POST /api/v1/auth/mfa/confirm
     * <p>
     * Validates the supplied 6-digit TOTP code against the pending secret and,
     * on success, activates MFA for the user ({@code totp_enabled = true}).
     *
     * <p>Requires a valid Bearer token.
     */
    @PostMapping("/mfa/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaConfirm(@RequestBody @Valid MfaConfirmRequest request) {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();
        mfaSetupService.confirm(email, request.code());
    }

    /**
     * GET /api/v1/auth/me
     * <p>
     * Returns the authenticated user's profile and organization
     * memberships.
     *
     * <p>Requires a valid Bearer token.
     */
    @GetMapping("/me")
    public MeResponse me() {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        // ── Organizations ────────────────────────────────────────────────────
        List<MeResponse.OrgInfo> organizations = orgMemberRepository
                .findAllByUserIdWithOrganization(user.getId())
                .stream()
                .map(m -> new MeResponse.OrgInfo(
                        m.getOrganization().getId(),
                        m.getOrganization().getName(),
                        m.getRole().name()))
                .toList();

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.isFirstLogin(),
                user.isVerified(),
                user.getCurrentOrganizationId(),
                organizations,
                user.getVerificationStatus() != null
                        ? user.getVerificationStatus().name()
                        : User.VerificationStatus.UNVERIFIED.name()
        );
    }

    // ── Organization switching ────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/switch-org
     * <p>
     * Switches the authenticated user's active organization.  Validates that
     * the user is a member of the target org, updates {@code currentOrganizationId},
     * and re-issues a JWT with the new {@code orgId} claim.
     */
    @PostMapping("/switch-org")
    public LoginResponse switchOrg(@RequestBody SwitchOrgRequest request) {
        String email = watch.nabu.application.security.SecurityContextService.currentEmail();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        UUID targetOrgId = request.organizationId();
        if (!orgMemberRepository.existsByOrganizationIdAndUserId(targetOrgId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Not a member of organization " + targetOrgId);
        }

        orgRepository.findById(targetOrgId)
                .filter(org -> org.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Organization is disabled"));

        user.setCurrentOrganizationId(targetOrgId);
        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        String jwt = jwtService.generateToken(userDetails, targetOrgId);

        log.info("[auth] Org switched: userId={} newOrgId={}", user.getId(), targetOrgId);

        return new LoginResponse(
                jwt, "Bearer", jwtService.expiryOf(jwt),
                user.getId(), user.getRole().name(), false);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/register
     * <p>
     * Creates a new email/password account in PENDING_VERIFICATION state,
     * along with a personal org.
     * Sends a verification email — the account cannot log in until verified.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody @Valid RegisterRequest request, HttpServletRequest httpRequest) {
        authRateLimitService.checkRegisterRate(HttpRequestUtils.clientIp(httpRequest));
        registrationService.register(request.email(), request.password(), request.name());
        return Map.of("message", "If this email is not already registered, you will receive a verification link shortly.");
    }

    /**
     * POST /api/v1/auth/verify-email
     * <p>
     * Consumes the single-use token from the verification link, activates the
     * account, and returns a JWT so the user is immediately logged in.
     */
    @PostMapping("/verify-email")
    public Map<String, Object> verifyEmail(@RequestBody @Valid VerifyEmailRequest request) {
        UUID userId = registrationService.verifyEmail(request.token());
        User user = userRepository.findById(userId).orElseThrow();
        // User is now ACTIVE — UserDetails load will succeed
        var userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String jwt = jwtService.generateToken(userDetails, false, user.getCurrentOrganizationId());
        return Map.of("token", jwt, "message", "Email verified successfully.");
    }

    // ── Email verification ────────────────────────────────────────────────────

    /**
     * POST /api/v1/auth/resend-verification
     * <p>
     * Sends a fresh verification link to the given address if the account is
     * still pending.  Always returns 200 to avoid leaking whether the address exists.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> resendVerification(@RequestBody ResendVerificationRequest request) {
        if (request.email() != null && !request.email().isBlank()) {
            authRateLimitService.checkVerifyResendRate(request.email());
        }
        registrationService.resendVerification(request.email());
        return Map.of("message", "If the email exists, a new verification link has been sent.");
    }

    /**
     * POST /api/v1/auth/forgot-password
     * <p>
     * Triggers a password-reset email for an ACTIVE account.
     * Always returns 200 to avoid leaking whether the address exists.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        if (request.email() != null && !request.email().isBlank()) {
            authRateLimitService.checkResetRate(request.email());
        }
        registrationService.forgotPassword(request.email());
        return Map.of("message", "If the email exists, a password reset link has been sent.");
    }

    /**
     * POST /api/v1/auth/reset-password
     * <p>
     * Consumes the single-use reset token and replaces the user's password.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        registrationService.resetPassword(request.token(), request.newPassword());
        return Map.of("message", "Password reset successfully. You can now log in with your new password.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return problem;
    }

    // ── Password reset ────────────────────────────────────────────────────────

    record RegisterRequest(
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 8, max = 128)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                    message = "Password must contain at least one uppercase letter, one lowercase letter, and one number"
            ) String password,
            String name
    ) {
    }

    record VerifyEmailRequest(
            @jakarta.validation.constraints.NotBlank String token
    ) {
    }

    record ResendVerificationRequest(
            @jakarta.validation.constraints.Email String email
    ) {
    }

    record ForgotPasswordRequest(
            @jakarta.validation.constraints.Email String email
    ) {
    }

    // ── auth exception handlers (scoped to this controller) ──────────────────

    record ResetPasswordRequest(
            @jakarta.validation.constraints.NotBlank String token,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 8, max = 128)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
                    message = "Password must contain at least one uppercase letter, one lowercase letter, and one number"
            ) String newPassword
    ) {
    }

    @ExceptionHandler({DisabledException.class, LockedException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ProblemDetail handleAccountNotActive(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Account is not active. Please contact support.");
        problem.setTitle("Account Inactive");
        return problem;
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleEmailNotVerified(EmailNotVerifiedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Please verify your email before signing in. Check your inbox for a verification link.");
        problem.setTitle("Email Not Verified");
        problem.setProperty("errorCode", "EMAIL_NOT_VERIFIED");
        return problem;
    }

    @ExceptionHandler(LoginFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ProblemDetail handleLoginFailed(LoginFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid email or password");
        problem.setTitle("Login Failed");
        return problem;
    }

    private static class EmailNotVerifiedException extends RuntimeException {
    }

    private static class LoginFailedException extends RuntimeException {
    }
}
