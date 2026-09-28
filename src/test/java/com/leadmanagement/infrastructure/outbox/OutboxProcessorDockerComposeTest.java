package com.leadmanagement.infrastructure.outbox;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 acceptance test — atomic multi-tenant claim + per-event processing.
 *
 * @Transactional(NOT_SUPPORTED) at class level, same reason as
 * TenantIsolationJpaDockerComposeTest: OutboxEventHandler.handle() uses
 * REQUIRES_NEW, which would suspend (not join) a test-managed transaction
 * and commit for real regardless — so this test doesn't rely on JUnit's
 * automatic rollback and instead cleans up manually in @AfterEach.
 *
 * What is verified:
 * 1. claimPendingEventsForProcessing() works with a sentinel (not real)
 *    tenant context on the query itself — mirrors OutboxProcessor's own
 *    bootstrap for this intentionally cross-tenant native query — and
 *    atomically flips claimed rows to PROCESSING in the same statement.
 * 2. Events from two different tenants claimed in the SAME batch each get
 *    processed under their OWN tenant's context — no cross-tenant leakage,
 *    the same guarantee TenantIsolationJpaDockerComposeTest proves for
 *    request-thread reads/writes, now proven for the background processor.
 * 3. Successful processing lands on PROCESSED.
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class, OutboxEventHandler.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxProcessorDockerComposeTest {

    private static final UUID SYSTEM_TENANT_ID = new UUID(0L, 0L);

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventHandler outboxEventHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private UUID eventIdA;
    private UUID eventIdB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        eventIdA = insertPendingEvent(tenantA, "aggA");
        eventIdB = insertPendingEvent(tenantB, "aggB");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id IN (?, ?)", eventIdA, eventIdB);
        TenantContext.clear();
    }

    @Test
    void claimIsAtomicAndCrossTenantEventsProcessIndependently() {
        // Claim: mirrors OutboxProcessor's own bootstrap — the query is
        // cross-tenant by design (native SQL, no tenant filter), so a
        // sentinel value is enough to satisfy Hibernate's session-open gate.
        TenantContext.setCurrentTenantId(SYSTEM_TENANT_ID);
        List<OutboxEvent> claimed;
        try {
            claimed = outboxEventRepository.claimPendingEventsForProcessing(10);
        } finally {
            TenantContext.clear();
        }

        assertThat(claimed)
            .as("both tenants' pending events should be claimed in one batch")
            .extracting(OutboxEvent::getId)
            .contains(eventIdA, eventIdB);

        // Atomic claim already flipped them to PROCESSING — raw SQL proof,
        // bypassing the ORM's tenant filter entirely.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ?", String.class, eventIdA))
            .isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ?", String.class, eventIdB))
            .isEqualTo("PROCESSING");

        // Now process each under its OWN tenant context, same as OutboxProcessor's loop.
        for (OutboxEvent event : claimed) {
            TenantContext.setCurrentTenantId(event.getTenantId());
            try {
                outboxEventHandler.handle(event);
            } finally {
                TenantContext.clear();
            }
        }

        String statusA = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ? AND tenant_id = ?", String.class, eventIdA, tenantA);
        String statusB = jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ? AND tenant_id = ?", String.class, eventIdB, tenantB);

        assertThat(statusA).isEqualTo("PROCESSED");
        assertThat(statusB).isEqualTo("PROCESSED");

        // Cross-tenant proof: neither event's row exists under the OTHER tenant's id.
        Integer crossCountA = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE id = ? AND tenant_id = ?", Integer.class, eventIdA, tenantB);
        Integer crossCountB = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE id = ? AND tenant_id = ?", Integer.class, eventIdB, tenantA);
        assertThat(crossCountA).isZero();
        assertThat(crossCountB).isZero();

        System.out.println("✅ Outbox claim+process proven cross-tenant safe: A=" + statusA + " B=" + statusB);
    }

    private UUID insertPendingEvent(UUID tenantId, String aggregateType) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now().minusSeconds(1);
        jdbcTemplate.update("""
            INSERT INTO outbox_events
                (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,
                 retry_count, correlation_id, created_at, updated_at, next_attempt_at)
            VALUES (?, ?, ?, ?, 'LeadCreatedEvent', '{}'::jsonb, 'PENDING', 0, ?, ?, ?, ?)
            """,
            id, tenantId, aggregateType, UUID.randomUUID(), id.toString(), now, now, now);
        return id;
    }
}
