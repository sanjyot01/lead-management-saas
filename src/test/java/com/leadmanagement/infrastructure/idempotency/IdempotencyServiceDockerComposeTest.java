package com.leadmanagement.infrastructure.idempotency;

import com.leadmanagement.infrastructure.config.AuditingConfig;
import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 acceptance test — reserve-first idempotency against real Postgres.
 *
 * @Transactional(NOT_SUPPORTED) at class level: every IdempotencyService
 * method is its own committing transaction (that separation is the point of
 * the design — see the service's class comment), so this test cannot rely on
 * JUnit rollback and cleans up manually, same as the other compose tests.
 *
 * What is verified:
 * 1. claim() durably inserts an in-flight row BEFORE any processing
 *    (raw SQL proof: response_status IS NULL).
 * 2. A second claim() for the same (tenant, key) throws — the unique
 *    constraint, not application logic, is what prevents double execution.
 * 3. resolveExisting() classifies every state correctly: in-flight,
 *    completed (replay), different-body reuse (mismatch), expired (takeover).
 * 4. The same key under two different tenants yields two independent
 *    records — the @TenantId analogue of every other isolation proof.
 * 5. release() really deletes, so a failed request's retry can reclaim.
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class, IdempotencyService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServiceDockerComposeTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private String key;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        key = "test-key-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        TenantContext.clear();
    }

    @Test
    void claimInsertsDurableInFlightRowBeforeProcessing() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");

        // Raw SQL proof: the claim is committed and in flight (no response yet)
        // BEFORE any request processing would have happened.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ? AND response_status IS NULL",
            Integer.class, tenantA, key)).isEqualTo(1);

        assertThat(idempotencyService.resolveExisting(key, "hash-1"))
            .isInstanceOf(IdempotencyClaimResult.InFlight.class);
    }

    @Test
    void duplicateClaimIsRejectedByTheUniqueConstraint() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");

        assertThatThrownBy(() -> idempotencyService.claim(key, "hash-1"))
            .as("the database constraint, not application logic, must reject the second claim")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completedResponseIsReplayedForSameKeyAndHash() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");
        idempotencyService.complete(key, 201, "{\"id\":\"lead-123\"}");

        IdempotencyClaimResult result = idempotencyService.resolveExisting(key, "hash-1");

        assertThat(result).isInstanceOf(IdempotencyClaimResult.Replay.class);
        IdempotencyClaimResult.Replay replay = (IdempotencyClaimResult.Replay) result;
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).isEqualTo("{\"id\":\"lead-123\"}");

        System.out.println("✅ Idempotent replay proven: " + replay.responseStatus() + " " + replay.responseBody());
    }

    @Test
    void sameKeyWithDifferentBodyIsAMismatchNeverAReplay() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");
        idempotencyService.complete(key, 201, "{\"id\":\"lead-123\"}");

        assertThat(idempotencyService.resolveExisting(key, "DIFFERENT-hash"))
            .isInstanceOf(IdempotencyClaimResult.HashMismatch.class);
    }

    @Test
    void expiredClaimIsTakenOverAsANewRequest() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");
        idempotencyService.complete(key, 201, "{\"id\":\"old\"}");

        // Age the record past its replay window.
        jdbcTemplate.update(
            "UPDATE idempotency_keys SET expires_at = NOW() - INTERVAL '1 minute' WHERE tenant_id = ? AND idempotency_key = ?",
            tenantA, key);

        assertThat(idempotencyService.resolveExisting(key, "hash-2"))
            .as("an expired key must behave like a brand-new request")
            .isInstanceOf(IdempotencyClaimResult.NewClaim.class);

        // The row restarted its lifecycle: in flight again, new hash.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT request_hash FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ? AND response_status IS NULL",
            String.class, tenantA, key)).isEqualTo("hash-2");
    }

    @Test
    void sameKeyUnderTwoTenantsIsTwoIndependentRecords() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-A");
        idempotencyService.complete(key, 201, "{\"tenant\":\"A\"}");

        // Tenant B reusing the exact same key string is NOT a duplicate.
        TenantContext.setCurrentTenantId(tenantB);
        idempotencyService.claim(key, "hash-B");

        // Tenant B's resolve sees only its own in-flight record, never
        // tenant A's completed response.
        assertThat(idempotencyService.resolveExisting(key, "hash-B"))
            .isInstanceOf(IdempotencyClaimResult.InFlight.class);

        // Raw SQL proof: two rows, one per tenant.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?",
            Integer.class, key)).isEqualTo(2);

        System.out.println("✅ Idempotency keys proven tenant-isolated: same key, 2 independent rows");
    }

    @Test
    void releaseDeletesTheClaimSoARetryCanReclaim() {
        TenantContext.setCurrentTenantId(tenantA);
        idempotencyService.claim(key, "hash-1");
        idempotencyService.release(key);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?",
            Integer.class, tenantA, key)).isZero();

        // The retry gets a genuine second attempt.
        idempotencyService.claim(key, "hash-1");
    }
}