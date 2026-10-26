package watch.nabu.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Monitor.MonitorType;
import watch.nabu.domain.model.Monitor.SubType;
import watch.nabu.domain.model.Organization;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.User.AccountStatus;
import watch.nabu.domain.model.Website;
import watch.nabu.domain.model.Website.WebsiteStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MonitorRepositoryTest extends AbstractRepositoryTest {

    @Autowired TestEntityManager  em;
    @Autowired MonitorRepository  monitorRepository;

    private Organization org;
    private User    user;
    private Website activeWebsite;
    private Website pausedWebsite;

    @BeforeEach
    void seed() {
        org = em.persist(Organization.builder()
                .name("Monitor Test Org")
                .build());

        user = em.persist(User.builder()
            .fullName("Test User")
            .email("monitor-test@example.com")
            .passwordHash("$2a$10$testhash")
            .status(AccountStatus.ACTIVE)
                .currentOrganizationId(org.getId())
            .build());

        activeWebsite = em.persist(Website.builder()
                .organization(org)
                .addedBy(user)
            .url("https://active.example.de")
            .regionCode("DE")
            .status(WebsiteStatus.ACTIVE)
            .build());

        pausedWebsite = em.persist(Website.builder()
                .organization(org)
                .addedBy(user)
            .url("https://paused.example.de")
            .regionCode("DE")
            .status(WebsiteStatus.PAUSED)
            .build());

        em.flush();
    }

    // ── findOverdueMonitorIds ─────────────────────────────────────────────────

    @Test
    void neverRunMonitor_isOverdue() {
        Monitor m = em.persist(sslMonitor(activeWebsite, null));
        em.flush();

        List<UUID> ids = monitorRepository.findOverdueMonitorIds(100);

        assertThat(ids).contains(m.getId());
    }

    @Test
    void monitorWithExpiredLastRunAt_isOverdue() {
        // intervalMinutes = 60; lastRunAt = 2 hours ago → overdue
        Instant twoHoursAgo = Instant.now().minusSeconds(7200);
        Monitor m = em.persist(sslMonitor(activeWebsite, twoHoursAgo));
        em.flush();

        assertThat(monitorRepository.findOverdueMonitorIds(100)).contains(m.getId());
    }

    @Test
    void monitorWithRecentLastRunAt_isNotOverdue() {
        // intervalMinutes = 60; lastRunAt = 10 minutes ago → still valid
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);
        Monitor m = em.persist(sslMonitor(activeWebsite, tenMinutesAgo));
        em.flush();

        assertThat(monitorRepository.findOverdueMonitorIds(100)).doesNotContain(m.getId());
    }

    @Test
    void overdueMonitorForPausedWebsite_isExcluded() {
        // Monitor belongs to a PAUSED website — must not be dispatched
        Monitor m = em.persist(sslMonitor(pausedWebsite, null));
        em.flush();

        assertThat(monitorRepository.findOverdueMonitorIds(100)).doesNotContain(m.getId());
    }

    @Test
    void limitIsRespected() {
        // Create 5 overdue monitors; query with limit 3
        for (int i = 0; i < 5; i++) {
            em.persist(sslMonitor(activeWebsite, null));
        }
        em.flush();

        List<UUID> ids = monitorRepository.findOverdueMonitorIds(3);

        assertThat(ids).hasSize(3);
    }

    @Test
    void overdueMonitorsReturnedOldestFirst() {
        // Three monitors, oldest lastRunAt first
        Instant t1 = Instant.now().minusSeconds(7200); // 2h ago
        Instant t2 = Instant.now().minusSeconds(5400); // 1.5h ago
        Instant t3 = Instant.now().minusSeconds(3700); // ~1h ago

        Monitor older   = em.persist(sslMonitor(activeWebsite, t1));
        Monitor middle  = em.persist(sslMonitor(activeWebsite, t2));
        Monitor younger = em.persist(sslMonitor(activeWebsite, t3));
        em.flush();

        List<UUID> ids = monitorRepository.findOverdueMonitorIds(100);

        // Never-run (null) and oldest-run come first; just verify all are present
        assertThat(ids).containsAll(List.of(older.getId(), middle.getId(), younger.getId()));

        // Ordering: older must appear before younger
        assertThat(ids.indexOf(older.getId())).isLessThan(ids.indexOf(younger.getId()));
    }

    // ── findAllWithWebsiteByIds ───────────────────────────────────────────────

    @Test
    void findAllWithWebsiteByIds_loadsWebsiteEagerly() {
        Monitor m = em.persist(sslMonitor(activeWebsite, null));
        em.flush();
        em.clear(); // detach — forces Hibernate to actually load

        List<Monitor> result = monitorRepository.findAllWithWebsiteByIds(List.of(m.getId()));

        assertThat(result).hasSize(1);
        // Accessing website should NOT throw LazyInitializationException
        assertThat(result.get(0).getWebsite().getUrl()).isEqualTo("https://active.example.de");
    }

    @Test
    void findAllWithWebsiteByIds_returnsEmptyForUnknownIds() {
        assertThat(monitorRepository.findAllWithWebsiteByIds(List.of(UUID.randomUUID()))).isEmpty();
    }

    // ── findByWebsiteId ───────────────────────────────────────────────────────

    @Test
    void findByWebsiteId_returnsOnlyMonitorsForThatWebsite() {
        Monitor m1 = em.persist(sslMonitor(activeWebsite, null));
        Monitor m2 = em.persist(complianceMonitor(activeWebsite, SubType.IMPRESSUM));
        em.persist(sslMonitor(pausedWebsite, null)); // belongs to a different website
        em.flush();

        List<Monitor> result = monitorRepository.findByWebsiteId(activeWebsite.getId());

        assertThat(result)
            .hasSize(2)
            .extracting(Monitor::getId)
            .containsExactlyInAnyOrder(m1.getId(), m2.getId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Monitor sslMonitor(Website website, Instant lastRunAt) {
        return Monitor.builder()
            .website(website)
            .type(MonitorType.SSL)
            .subType(SubType.SSL)
            .intervalMinutes(60)
            .lastRunAt(lastRunAt)
            .build();
    }

    private static Monitor complianceMonitor(Website website, SubType subType) {
        return Monitor.builder()
            .website(website)
            .type(MonitorType.COMPLIANCE)
            .subType(subType)
            .intervalMinutes(1440)
            .build();
    }
}
