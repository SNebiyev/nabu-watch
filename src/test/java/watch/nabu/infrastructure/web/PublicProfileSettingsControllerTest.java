package watch.nabu.infrastructure.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.domain.model.User.VerificationStatus;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicProfileSettingsControllerTest {

    @Mock SecurityContextService securityContext;
    @Mock UserRepository userRepository;
    @InjectMocks PublicProfileSettingsController controller;

    private watch.nabu.domain.model.User user() {
        var u = watch.nabu.domain.model.User.builder().email("u@x").fullName("U").build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setVerificationStatus(VerificationStatus.UNVERIFIED);
        return u;
    }

    private static org.springframework.security.core.userdetails.UserDetails principal() {
        return User.withUsername("u@x").password("x").authorities("ROLE_USER").build();
    }

    @Test
    @DisplayName("update sets slug and echoes it back")
    void setSlug() {
        var u = user();
        when(securityContext.resolveUser("u@x")).thenReturn(u);
        when(userRepository.findByPublicUsernameIgnoreCase("acme")).thenReturn(Optional.empty());

        var res = controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest("acme", null));

        assertThat(res.publicUsername()).isEqualTo("acme");
        assertThat(u.getPublicUsername()).isEqualTo("acme");
    }

    @Test
    @DisplayName("update rejects invalid slug with 400")
    void rejectBadSlug() {
        var u = user();
        when(securityContext.resolveUser("u@x")).thenReturn(u);

        assertThatThrownBy(() -> controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest("-bad-", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    @DisplayName("update returns 409 when slug is taken by another user")
    void conflictOnTakenSlug() {
        var u = user();
        var other = user();
        other.setPublicUsername("taken");
        when(securityContext.resolveUser("u@x")).thenReturn(u);
        when(userRepository.findByPublicUsernameIgnoreCase("taken")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest("taken", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    @DisplayName("update with existing slug owned by same user is idempotent (no 409)")
    void idempotentWhenSameOwner() {
        var u = user();
        u.setPublicUsername("mine");
        when(securityContext.resolveUser("u@x")).thenReturn(u);
        when(userRepository.findByPublicUsernameIgnoreCase("mine")).thenReturn(Optional.of(u));

        var res = controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest("mine", null));
        assertThat(res.publicUsername()).isEqualTo("mine");
    }

    @Test
    @DisplayName("empty slug clears publicUsername and disables profile")
    void clearSlug() {
        var u = user();
        u.setPublicUsername("old");
        u.setPublicProfileEnabled(true);
        when(securityContext.resolveUser("u@x")).thenReturn(u);

        var res = controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest("", null));
        assertThat(res.publicUsername()).isNull();
        assertThat(res.publicProfileEnabled()).isFalse();
    }

    @Test
    @DisplayName("enabling without a slug returns 400")
    void cantEnableWithoutSlug() {
        var u = user();
        when(securityContext.resolveUser("u@x")).thenReturn(u);

        assertThatThrownBy(() -> controller.update(principal(),
                new PublicProfileSettingsController.PublicProfileRequest(null, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("public username");
    }
}
