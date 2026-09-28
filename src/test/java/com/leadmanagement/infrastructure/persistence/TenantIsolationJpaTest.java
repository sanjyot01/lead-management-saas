package com.leadmanagement.infrastructure.persistence;

import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.user.domain.User;
import com.leadmanagement.user.domain.UserRole;
import com.leadmanagement.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Disabled("Superseded by TenantIsolationJpaDockerComposeTest: Docker Desktop on this machine returns HTTP 400 to docker-java's npipe requests, so Testcontainers cannot start containers. The compose-based test covers the same scenarios against the docker-compose Postgres.")
@DataJpaTest
@Testcontainers
@Import({JpaConfig.class, CurrentTenantResolver.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantIsolationJpaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("leadmanagement")
        .withUsername("leaduser")
        .withPassword("leadpass123");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUpTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        insertTenant(tenantA, "tenant-a");
        insertTenant(tenantB, "tenant-b");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void shouldInjectTenantIdAndFilterQueriesByCurrentTenant() {
        User tenantAUser = saveUserForTenant(tenantA, "shared@leadflow.com", "Bruce");
        User tenantBUser = saveUserForTenant(tenantB, "shared@leadflow.com", "Tony");

        assertThat(tenantAUser.getTenantId()).isEqualTo(tenantA);
        assertThat(tenantBUser.getTenantId()).isEqualTo(tenantB);

        TenantContext.setCurrentTenantId(tenantA);
        Optional<User> foundInTenantA = userRepository.findByEmail("shared@leadflow.com");

        TenantContext.setCurrentTenantId(tenantB);
        Optional<User> foundInTenantB = userRepository.findByEmail("shared@leadflow.com");

        assertThat(foundInTenantA).isPresent();
        assertThat(foundInTenantB).isPresent();
        assertThat(foundInTenantA.get().getId()).isEqualTo(tenantAUser.getId());
        assertThat(foundInTenantB.get().getId()).isEqualTo(tenantBUser.getId());
        assertThat(foundInTenantA.get().getId()).isNotEqualTo(foundInTenantB.get().getId());
    }

    private User saveUserForTenant(UUID tenantId, String email, String firstName) {
        TenantContext.setCurrentTenantId(tenantId);
        User user = new User(email, "$2a$10$dummy.hash.for.tests.only", firstName, "User", UserRole.ADMIN);
        return userRepository.saveAndFlush(user);
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbcTemplate.update(
            """
                INSERT INTO tenants (id, name, slug, status, subscription_tier, max_leads, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
            """,
            tenantId,
            "Tenant " + slug,
            slug,
            "TRIAL",
            "STARTER",
            1000
        );
    }
}

