package watch.nabu.application.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.domain.model.OrgNotificationSetting;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.OrgNotificationSettingRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgNotificationSettingsServiceTest {

    @Mock
    private OrgNotificationSettingRepository settingRepository;

    @InjectMocks
    private OrgNotificationSettingsService service;

    @Test
    @DisplayName("initDefaults: OWNER role gets both flags true")
    void initDefaultsOwnerGetsTrue() {
        Organization org = org("TestOrg");
        User user = user("owner@example.com");

        when(settingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initDefaults(org, user, OrganizationMember.Role.OWNER);

        ArgumentCaptor<OrgNotificationSetting> captor = ArgumentCaptor.forClass(OrgNotificationSetting.class);
        verify(settingRepository).save(captor.capture());
        OrgNotificationSetting saved = captor.getValue();
        assertThat(saved.isAlertsEnabled()).isTrue();
        assertThat(saved.isDigestEnabled()).isTrue();
    }

    @Test
    @DisplayName("initDefaults: MEMBER role gets both flags false")
    void initDefaultsMemberGetsFalse() {
        Organization org = org("TestOrg");
        User user = user("member@example.com");

        when(settingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.initDefaults(org, user, OrganizationMember.Role.MEMBER);

        ArgumentCaptor<OrgNotificationSetting> captor = ArgumentCaptor.forClass(OrgNotificationSetting.class);
        verify(settingRepository).save(captor.capture());
        OrgNotificationSetting saved = captor.getValue();
        assertThat(saved.isAlertsEnabled()).isFalse();
        assertThat(saved.isDigestEnabled()).isFalse();
    }

    @Test
    @DisplayName("updateByOwner: partial update only changes non-null fields")
    void updateByOwnerPartialUpdate() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrgNotificationSetting existing = OrgNotificationSetting.builder()
                .alertsEnabled(false)
                .digestEnabled(false)
                .build();

        when(settingRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.of(existing));
        when(settingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrgNotificationSetting result = service.updateByOwner(orgId, userId, true, null);

        assertThat(result.isAlertsEnabled()).isTrue();
        assertThat(result.isDigestEnabled()).isFalse(); // unchanged
    }

    @Test
    @DisplayName("updateByOwner: throws NoSuchElementException when setting not found")
    void updateByOwnerNotFound() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(settingRepository.findByOrganizationIdAndUserId(orgId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateByOwner(orgId, userId, true, true))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("getAlertEnabledUserIds returns correct user IDs")
    void getAlertEnabledUserIds() {
        UUID orgId = UUID.randomUUID();
        User u1 = user("a@e.com");
        User u2 = user("b@e.com");

        when(settingRepository.findAlertEnabledUserIdsByOrganizationId(orgId))
                .thenReturn(List.of(u1.getId(), u2.getId()));

        Set<UUID> result = service.getAlertEnabledUserIds(orgId);

        assertThat(result).containsExactlyInAnyOrder(u1.getId(), u2.getId());
    }

    @Test
    @DisplayName("getDigestEnabledOrgIds returns correct org IDs")
    void getDigestEnabledOrgIds() {
        UUID userId = UUID.randomUUID();
        Organization org1 = org("Org1");
        Organization org2 = org("Org2");

        when(settingRepository.findDigestEnabledOrgIdsByUserId(userId))
                .thenReturn(List.of(org1.getId(), org2.getId()));

        Set<UUID> result = service.getDigestEnabledOrgIds(userId);

        assertThat(result).containsExactlyInAnyOrder(org1.getId(), org2.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User user(String email) {
        User u = User.builder().email(email).fullName("Test " + email).build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private Organization org(String name) {
        Organization o = Organization.builder().name(name).enabled(true).build();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        return o;
    }
}
