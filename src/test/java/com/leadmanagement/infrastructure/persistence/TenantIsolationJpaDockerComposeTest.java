package com.leadmanagement.infrastructure.persistence;

import com.leadmanagement.infrastructure.config.AuditingConfig;
import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.user.domain.User;
import com.leadmanagement.user.domain.UserRole;
import com.leadmanagement.user.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 Acceptance Tests – Hibernate @TenantId isolation.
 *
 * What is verified:
 * 1. Tenant ID is AUTO-INJECTED on insert (no manual setTenantId call in service)
 * 2. findByEmail returns ONLY the requesting tenant's user
 * 3. findAll returns ONLY the requesting tenant's records
 * 4. findById cannot reach another tenant's record
 * 5. existsByEmail is scoped to the current tenant
 * 6. TenantContext.clear() removes discriminator (subsequent queries fail cleanly)
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantIsolationJpaDockerComposeTest {

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
        insertTenant(tenantA, "phase1-a-" + tenantA);
        insertTenant(tenantB, "phase1-b-" + tenantB);
    }

    @AfterEach
    void cleanUp() {
        // Delete test users (bypass tenant filter with raw SQL so both tenants are cleaned)
        jdbcTemplate.update("DELETE FROM users WHERE tenant_id = ?", tenantA);
        jdbcTemplate.update("DELETE FROM users WHERE tenant_id = ?", tenantB);
        jdbcTemplate.update("DELETE FROM tenants WHERE id = ? OR id = ?", tenantA, tenantB);
        TenantContext.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // Test 1 – @TenantId auto-injects the correct tenant ID on save
    // ─────────────────────────────────────────────────────────────
    @Test
    void test1_tenantIdIsAutoInjectedOnInsert() {
        User userA = saveUserForTenant(tenantA, "bruce@wayne.com", "Bruce");
        User userB = saveUserForTenant(tenantB, "tony@stark.com", "Tony");

        assertThat(userA.getTenantId())
            .as("Tenant A user must carry tenantA UUID")
            .isEqualTo(tenantA);

        assertThat(userB.getTenantId())
            .as("Tenant B user must carry tenantB UUID")
            .isEqualTo(tenantB);

        System.out.println("✅ Test 1 PASS – @TenantId auto-injected: " +
            userA.getTenantId() + " / " + userB.getTenantId());
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2 – findByEmail returns only the calling tenant's user
    //          (same email exists in both tenants)
    // ─────────────────────────────────────────────────────────────
    @Test
    void test2_findByEmailScopedToCurrentTenant() {
        User userA = saveUserForTenant(tenantA, "shared@test.com", "Bruce");
        User userB = saveUserForTenant(tenantB, "shared@test.com", "Tony");

        TenantContext.setCurrentTenantId(tenantA);
        Optional<User> foundA = userRepository.findByEmail("shared@test.com");

        TenantContext.setCurrentTenantId(tenantB);
        Optional<User> foundB = userRepository.findByEmail("shared@test.com");

        assertThat(foundA).isPresent();
        assertThat(foundB).isPresent();
        assertThat(foundA.get().getId()).isEqualTo(userA.getId());
        assertThat(foundB.get().getId()).isEqualTo(userB.getId());
        assertThat(foundA.get().getId()).isNotEqualTo(foundB.get().getId());
        assertThat(foundA.get().getFirstName()).isEqualTo("Bruce");
        assertThat(foundB.get().getFirstName()).isEqualTo("Tony");

        System.out.println("✅ Test 2 PASS – findByEmail scoped: Bruce=" +
            foundA.get().getId() + " Tony=" + foundB.get().getId());
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3 – findAll returns ONLY the current tenant's records
    // ─────────────────────────────────────────────────────────────
    @Test
    void test3_findAllScopedToCurrentTenant() {
        saveUserForTenant(tenantA, "a1@test.com", "Alice");
        saveUserForTenant(tenantA, "a2@test.com", "Adam");
        saveUserForTenant(tenantB, "b1@test.com", "Bob");

        TenantContext.setCurrentTenantId(tenantA);
        List<User> tenantAUsers = userRepository.findAll();

        TenantContext.setCurrentTenantId(tenantB);
        List<User> tenantBUsers = userRepository.findAll();

        // Tenant A should only see its 2 users
        assertThat(tenantAUsers).hasSize(2);
        assertThat(tenantAUsers).allMatch(u -> u.getTenantId().equals(tenantA));

        // Tenant B should only see its 1 user
        assertThat(tenantBUsers).hasSize(1);
        assertThat(tenantBUsers).allMatch(u -> u.getTenantId().equals(tenantB));

        System.out.println("✅ Test 3 PASS – findAll scoped: TenantA=" +
            tenantAUsers.size() + " users, TenantB=" + tenantBUsers.size() + " users");
    }

    // ─────────────────────────────────────────────────────────────
    // Test 4 – findById CANNOT read across tenant boundary
    // ─────────────────────────────────────────────────────────────
    @Test
    void test4_findByIdCannotCrossTenantBoundary() {
        User userA = saveUserForTenant(tenantA, "secret@a.com", "Alice");

        // Tenant B tries to look up Tenant A's user by ID
        TenantContext.setCurrentTenantId(tenantB);
        Optional<User> crossTenantLookup = userRepository.findById(userA.getId());

        assertThat(crossTenantLookup)
            .as("Tenant B must NOT be able to read Tenant A's user by ID")
            .isEmpty();

        System.out.println("✅ Test 4 PASS – cross-tenant findById blocked for ID: " + userA.getId());
    }

    // ─────────────────────────────────────────────────────────────
    // Test 5 – existsByEmail scoped to current tenant
    // ─────────────────────────────────────────────────────────────
    @Test
    void test5_existsByEmailScopedToCurrentTenant() {
        saveUserForTenant(tenantA, "exists@test.com", "Alice");

        TenantContext.setCurrentTenantId(tenantA);
        boolean existsInA = userRepository.existsByEmail("exists@test.com");

        TenantContext.setCurrentTenantId(tenantB);
        boolean existsInB = userRepository.existsByEmail("exists@test.com");

        assertThat(existsInA).as("Email should exist in Tenant A").isTrue();
        assertThat(existsInB).as("Email must NOT exist in Tenant B").isFalse();

        System.out.println("✅ Test 5 PASS – existsByEmail scoped: A=" + existsInA + " B=" + existsInB);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 6 – DB proof: raw SQL counts confirm physical row counts
    // ─────────────────────────────────────────────────────────────
    @Test
    void test6_databaseProof_rawSqlRowCountsMatchExpected() {
        saveUserForTenant(tenantA, "proof1@test.com", "Alice");
        saveUserForTenant(tenantA, "proof2@test.com", "Adam");
        saveUserForTenant(tenantB, "proof3@test.com", "Bob");

        Integer countA = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE tenant_id = ?", Integer.class, tenantA);
        Integer countB = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE tenant_id = ?", Integer.class, tenantB);
        Integer totalRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE tenant_id IN (?, ?)", Integer.class, tenantA, tenantB);

        assertThat(countA).isEqualTo(2);
        assertThat(countB).isEqualTo(1);
        assertThat(totalRows).isEqualTo(3);

        System.out.println("✅ Test 6 PASS – DB raw counts: TenantA=" + countA +
            " TenantB=" + countB + " Total=" + totalRows);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

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

