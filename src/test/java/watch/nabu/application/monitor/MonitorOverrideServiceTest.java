package watch.nabu.application.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import watch.nabu.application.exception.MonitorNotFoundException;
import watch.nabu.domain.model.*;
import watch.nabu.domain.model.OrganizationMember.Role;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.infrastructure.persistence.MonitorRepository;
import watch.nabu.infrastructure.persistence.MonitorStatusOverrideRepository;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.ScanLogRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorOverrideServiceTest {

    @Mock
    MonitorRepository monitorRepository;
    @Mock
    ScanLogRepository scanLogRepository;
    @Mock
    MonitorStatusOverrideRepository overrideRepository;
    @Mock
    OrganizationMemberRepository memberRepository;
    @InjectMocks
    MonitorOverrideService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Organization org() {
        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        return org;
    }

    private static User user() {
        User u = User.builder().email("admin@example.com").fullName("Admin").build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        return u;
    }

    private static Website website(Organization org) {
        Website w = Website.builder().url("https://example.com").organization(org).build();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        return w;
    }

    private static Monitor monitor(Website website) {
        Monitor m = Monitor.builder()
                .website(website)
                .type(Monitor.MonitorType.COMPLIANCE)
                .subType(Monitor.SubType.PRIVACY_POLICY)
                .intervalMinutes(60)
                .build();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        return m;
    }

    private static ScanLog scanLog(Monitor monitor, Severity severity) {
        ScanLog sl = ScanLog.builder().monitor(monitor).severity(severity).build();
        ReflectionTestUtils.setField(sl, "id", UUID.randomUUID());
        return sl;
    }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void createOrUpdate_happyPath_createsOverride() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);
        ScanLog sl = scanLog(monitor, Severity.MEDIUM);

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(true);
        when(scanLogRepository.findById(sl.getId())).thenReturn(Optional.of(sl));
        when(overrideRepository.findByMonitorId(monitor.getId())).thenReturn(Optional.empty());
        when(overrideRepository.save(any())).thenAnswer(inv -> {
            MonitorStatusOverride saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        MonitorStatusOverride result = service.createOrUpdate(
                monitor.getId(), sl.getId(), Severity.INFO, "Acceptable for us", u);

        assertThat(result.getBaselineSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.getOverrideSeverity()).isEqualTo(Severity.INFO);
        assertThat(result.getNote()).isEqualTo("Acceptable for us");
        assertThat(result.getOverriddenBy()).isEqualTo(u);
        verify(overrideRepository).save(any());
    }

    @Test
    void createOrUpdate_existingOverride_updatesInPlace() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);
        ScanLog sl = scanLog(monitor, Severity.HIGH);

        MonitorStatusOverride existing = MonitorStatusOverride.builder()
                .monitor(monitor)
                .baselineSeverity(Severity.MEDIUM)
                .overrideSeverity(Severity.INFO)
                .scanLog(sl)
                .overriddenBy(u)
                .build();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(true);
        when(scanLogRepository.findById(sl.getId())).thenReturn(Optional.of(sl));
        when(overrideRepository.findByMonitorId(monitor.getId())).thenReturn(Optional.of(existing));
        when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MonitorStatusOverride result = service.createOrUpdate(
                monitor.getId(), sl.getId(), Severity.MEDIUM, null, u);

        assertThat(result.getId()).isEqualTo(existing.getId()); // same entity, upserted
        assertThat(result.getBaselineSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.getOverrideSeverity()).isEqualTo(Severity.MEDIUM);
    }

    // ── no plan gating ──────────────────────────────────────────────────────

    @Test
    void createOrUpdate_ownerOfAnyOrg_canOverrideWithoutPlan() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);
        ScanLog sl = scanLog(monitor, Severity.HIGH);

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(true);
        when(scanLogRepository.findById(sl.getId())).thenReturn(Optional.of(sl));
        when(overrideRepository.findByMonitorId(monitor.getId())).thenReturn(Optional.empty());
        when(overrideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MonitorStatusOverride result = service.createOrUpdate(
                monitor.getId(), sl.getId(), Severity.INFO, null, u);

        assertThat(result.getOverrideSeverity()).isEqualTo(Severity.INFO);
    }

    // ── role gating ─────────────────────────────────────────────────────────

    @Test
    void createOrUpdate_memberRole_throwsAccessDenied() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(false);

        assertThatThrownBy(() ->
                service.createOrUpdate(monitor.getId(), UUID.randomUUID(), Severity.INFO, null, u))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── monitor not found ───────────────────────────────────────────────────

    @Test
    void createOrUpdate_monitorNotFound_throws() {
        UUID fakeId = UUID.randomUUID();
        when(monitorRepository.findById(fakeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.createOrUpdate(fakeId, UUID.randomUUID(), Severity.INFO, null, user()))
                .isInstanceOf(MonitorNotFoundException.class);
    }

    // ── scanLog mismatch ────────────────────────────────────────────────────

    @Test
    void createOrUpdate_scanLogBelongsToDifferentMonitor_throws() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);
        Monitor otherMonitor = monitor(website);
        ScanLog sl = scanLog(otherMonitor, Severity.INFO);

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(true);
        when(scanLogRepository.findById(sl.getId())).thenReturn(Optional.of(sl));

        assertThatThrownBy(() ->
                service.createOrUpdate(monitor.getId(), sl.getId(), Severity.INFO, null, u))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    // ── remove ──────────────────────────────────────────────────────────────

    @Test
    void remove_happyPath_deletesOverride() {
        Organization org = org();
        User u = user();
        Website website = website(org);
        Monitor monitor = monitor(website);

        when(monitorRepository.findById(monitor.getId())).thenReturn(Optional.of(monitor));
        when(memberRepository.existsByOrganizationIdAndUserIdAndRole(org.getId(), u.getId(), Role.OWNER))
                .thenReturn(true);

        service.remove(monitor.getId(), u);

        verify(overrideRepository).deleteByMonitorId(monitor.getId());
    }

    // ── getOverride (read-only, no permission check) ────────────────────────

    @Test
    void getOverride_returnsOverrideIfExists() {
        UUID monitorId = UUID.randomUUID();
        MonitorStatusOverride override = MonitorStatusOverride.builder().build();
        when(overrideRepository.findByMonitorId(monitorId)).thenReturn(Optional.of(override));

        assertThat(service.getOverride(monitorId)).isPresent();
    }

    @Test
    void getOverride_returnsEmptyIfNone() {
        UUID monitorId = UUID.randomUUID();
        when(overrideRepository.findByMonitorId(monitorId)).thenReturn(Optional.empty());

        assertThat(service.getOverride(monitorId)).isEmpty();
    }
}
