package watch.nabu.infrastructure.persistence;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for {@code @DataJpaTest} slices that require a real PostgreSQL.
 *
 * <p>A single container instance is shared across all subclasses in the same
 * JVM run via a static initializer — started once and stopped by Testcontainers'
 * JVM shutdown hook.  This prevents per-class container restarts from invalidating
 * the cached Spring context (which would cause "connection refused" on the old port).
 * Liquibase migrations run automatically on the test schema before the first test.
 *
 * <p>Subclasses inherit {@code @DataJpaTest} via this class; they only need to
 * inject the repositories and {@code TestEntityManager} they actually use.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class AbstractRepositoryTest {

    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("nabu_watch_test")
            .withUsername("nabu")
            .withPassword("nabu");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
