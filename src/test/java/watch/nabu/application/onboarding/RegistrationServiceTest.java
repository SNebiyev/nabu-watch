package watch.nabu.application.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.messaging.EmailTemplateService;
import watch.nabu.application.messaging.Message;
import watch.nabu.application.messaging.MessageDispatcher;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;
import watch.nabu.infrastructure.redis.EmailVerificationTokenService;
import watch.nabu.infrastructure.redis.PasswordResetTokenService;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationMemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailVerificationTokenService verificationTokenService;
    @Mock PasswordResetTokenService resetTokenService;
    @Mock EmailTemplateService templateService;
    @Mock MessageDispatcher messageDispatcher;
    @Mock AdminSignupNotifier adminSignupNotifier;
    @Mock AdminBootstrapService adminBootstrapService;
    @InjectMocks RegistrationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:3000");
    }

    private void stubRendering() {
        when(templateService.render(any(), any(), anyMap()))
                .thenReturn(new EmailTemplateService.RenderedEmail("<p>x</p>", "x"));
    }

    private User pendingUser() {
        User u = User.builder().email("a@b.com").fullName("A")
                .status(User.AccountStatus.PENDING_VERIFICATION).role(User.Role.USER).build();
        ReflectionTestUtils.setField(u, "id", userId);
        return u;
    }

    @Test
    void register_smtpDown_stillSavesUserAndReturnsId() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
            return o;
        });
        when(verificationTokenService.createToken(userId)).thenReturn("tok");
        stubRendering();
        doThrow(new RuntimeException("Connection refused")).when(messageDispatcher).dispatch(any(Message.class));

        UUID result = service.register("A@b.com", "pw", "A");

        assertEquals(userId, result);
        verify(userRepository, atLeastOnce()).save(any(User.class));
        verify(memberRepository).save(any());
        verify(adminSignupNotifier).notifyOfSignup(any(User.class), eq("email-password"));
    }

    @Test
    void resendVerification_smtpDown_doesNotThrow() {
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(pendingUser()));
        when(verificationTokenService.createToken(userId)).thenReturn("tok");
        stubRendering();
        doThrow(new RuntimeException("down")).when(messageDispatcher).dispatch(any(Message.class));

        assertDoesNotThrow(() -> service.resendVerification("a@b.com"));
    }

    @Test
    void forgotPassword_smtpDown_doesNotThrow() {
        User u = pendingUser();
        u.setStatus(User.AccountStatus.ACTIVE);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(u));
        when(resetTokenService.createToken(userId)).thenReturn("tok");
        stubRendering();
        doThrow(new RuntimeException("down")).when(messageDispatcher).dispatch(any(Message.class));

        assertDoesNotThrow(() -> service.forgotPassword("a@b.com"));
    }
}
