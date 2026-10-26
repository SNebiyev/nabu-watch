package watch.nabu.infrastructure.persistence;

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

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WebsiteRepositoryTest extends AbstractRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired WebsiteRepository websiteRepository;

    // ── findAllByOrganizationId ─────────────────────────────────────────────

    private static Website website(Organization org, User user, String url, String region) {
        return Website.builder()
                .organization(org)
                .addedBy(user)
            .url(url)
            .regionCode(region)
            .build();
    }

    @Test
    void findAllByOrganizationId_returnsOnlyThatOrgsWebsites() {
        Organization org1 = em.persist(Organization.builder().name("Org A").build());
        Organization org2 = em.persist(Organization.builder().name("Org B").build());
        User user1 = em.persist(testUser("alice@example.com"));
        User user2 = em.persist(testUser("bob@example.com"));

        Website w1 = em.persist(website(org1, user1, "https://alice.com", "DE"));
        Website w2 = em.persist(website(org1, user1, "https://alice.org", "DE"));
        em.persist(website(org2, user2, "https://bob.com", "US"));
        em.flush();

        List<Website> results = websiteRepository.findAllByOrganizationId(org1.getId());

        assertThat(results)
            .hasSize(2)
            .extracting(Website::getId)
            .containsExactlyInAnyOrder(w1.getId(), w2.getId());
    }

    @Test
    void findAllByOrganizationId_returnsEmptyListWhenOrgHasNoWebsites() {
        Organization org = em.persist(Organization.builder().name("Empty Org").build());
        em.flush();

        assertThat(websiteRepository.findAllByOrganizationId(org.getId())).isEmpty();
    }

    // ── save / findById ───────────────────────────────────────────────────────

    @Test
    void findAllByOrganizationId_doesNotReturnOtherOrgsWebsites() {
        Organization org1 = em.persist(Organization.builder().name("Org Owner").build());
        Organization org2 = em.persist(Organization.builder().name("Org Other").build());
        User other  = em.persist(testUser("other@example.com"));
        em.persist(website(org2, other, "https://other.com", "GB"));
        em.flush();

        assertThat(websiteRepository.findAllByOrganizationId(org1.getId())).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static User testUser(String email) {
        return User.builder()
            .fullName("Test User")
            .email(email)
            .passwordHash("$2a$10$testhash")
            .status(AccountStatus.ACTIVE)
            .build();
    }

    @Test
    void savedWebsite_canBeFoundById() {
        Organization org = em.persist(Organization.builder().name("Find Org").build());
        User user    = em.persist(testUser("find@example.com"));
        Website site = websiteRepository.save(website(org, user, "https://find.com", "FR"));
        em.flush();
        em.clear();

        assertThat(websiteRepository.findById(site.getId()))
            .isPresent()
            .hasValueSatisfying(w -> {
                assertThat(w.getUrl()).isEqualTo("https://find.com");
                assertThat(w.getRegionCode()).isEqualTo("FR");
                assertThat(w.getStatus()).isEqualTo(Website.WebsiteStatus.ACTIVE);
                assertThat(w.getCreatedAt()).isNotNull();
            });
    }
}
