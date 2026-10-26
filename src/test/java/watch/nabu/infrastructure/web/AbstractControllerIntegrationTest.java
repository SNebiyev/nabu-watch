package watch.nabu.infrastructure.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import watch.nabu.application.scheduler.MonitoringScheduler;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;
import watch.nabu.infrastructure.security.NabuUserDetailsService;

/**
 * Base class for controller integration tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>Testcontainers setup (Postgres 18 + Redis 7)</li>
 *   <li>MockMvc for HTTP testing</li>
 *   <li>JWT auth helpers for user/admin tokens</li>
 *   <li>Data seed methods</li>
 *   <li>FK-safe cleanup</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class AbstractControllerIntegrationTest {

    @SuppressWarnings("resource")
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("nabu_watch_test")
                    .withUsername("nabu")
                    .withPassword("nabu");

    @SuppressWarnings("resource")
    @ServiceConnection("redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @MockitoBean
    protected MonitoringScheduler monitoringScheduler;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected OrganizationRepository organizationRepository;

    @Autowired
    protected OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    protected WebsiteRepository websiteRepository;

    @Autowired
    protected MonitorRepository monitorRepository;

    @Autowired
    protected ScanLogRepository scanLogRepository;

    @Autowired
    protected NotificationRepository notificationRepository;

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /**
     * Mock auth for a regular user with ROLE_USER.
     * Sets up Spring Security context with authenticated user.
     * User must exist in DB for endpoints that call userRepository.findByEmail().
     */
    protected RequestPostProcessor userToken(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email)
                .authorities(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(NabuUserDetailsService.VERIFIED_AUTHORITY));
    }

    /**
     * Mock auth for an admin with both ROLE_USER and ROLE_ADMIN.
     * Sets up Spring Security context with authenticated admin user.
     * User must exist in DB for endpoints that call userRepository.findByEmail().
     */
    protected RequestPostProcessor adminToken(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email)
                .authorities(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(NabuUserDetailsService.VERIFIED_AUTHORITY));
    }

    // ── Data seeding ──────────────────────────────────────────────────────────

    /**
     * Seed a fully configured user (ACTIVE status) + creates their default org.
     * Returns the saved user with ID and currentOrganizationId set.
     */
    protected User seedUser(String email, User.Role role) {
        User user = User.builder()
                .fullName("Test User " + email)
                .email(email)
                .passwordHash("$2a$10$testhash")
                .role(role)
                .status(User.AccountStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        // Create org and membership
        Organization org = organizationRepository.save(
                Organization.builder().name("Org for " + email).build());
        organizationMemberRepository.save(
                OrganizationMember.builder()
                        .organization(org)
                        .user(user)
                        .role(OrganizationMember.Role.OWNER)
                        .build());

        // Set currentOrganizationId
        user.setCurrentOrganizationId(org.getId());
        return userRepository.save(user);
    }

    /**
     * Seed a website with 2 monitors (SSL + IMPRESSUM).
     */
    protected Website seedWebsite(Organization org, User addedBy, String url) {
        Website website = websiteRepository.save(
                Website.builder()
                        .organization(org)
                        .addedBy(addedBy)
                        .url(url)
                        .regionCode("DE")
                        .status(Website.WebsiteStatus.ACTIVE)
                        .build());

        // SSL monitor (type=SSL, subType=SSL, always 1440 min)
        monitorRepository.save(
                Monitor.builder()
                        .website(website)
                        .type(Monitor.MonitorType.SSL)
                        .subType(Monitor.SubType.SSL)
                        .intervalMinutes(1440)
                        .build());

        // Compliance monitor (type=COMPLIANCE, subType=IMPRESSUM, 1440 min)
        monitorRepository.save(
                Monitor.builder()
                        .website(website)
                        .type(Monitor.MonitorType.COMPLIANCE)
                        .subType(Monitor.SubType.IMPRESSUM)
                        .intervalMinutes(1440)
                        .build());

        return website;
    }

    /**
     * Seed a second user in the same org as the first.
     */
    protected User seedOrgMember(Organization org, String email) {
        User user = User.builder()
                .fullName("Test Member " + email)
                .email(email)
                .passwordHash("$2a$10$testhash")
                .role(User.Role.USER)
                .status(User.AccountStatus.ACTIVE)
                .currentOrganizationId(org.getId())
                .build();
        user = userRepository.save(user);

        organizationMemberRepository.save(
                OrganizationMember.builder()
                        .organization(org)
                        .user(user)
                        .role(OrganizationMember.Role.MEMBER)
                        .build());

        return user;
    }

    // ── Cleanup (FK-safe order) ───────────────────────────────────────────────

    @BeforeEach
    void beforeEachTest() {
        // Subclasses can override to add custom setup
    }

    @AfterEach
    void afterEachTest() {
        notificationRepository.deleteAll();
        scanLogRepository.deleteAll();
        monitorRepository.deleteAll();
        websiteRepository.deleteAll();
        organizationMemberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }
}
