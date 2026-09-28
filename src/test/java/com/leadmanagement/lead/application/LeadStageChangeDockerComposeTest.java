package com.leadmanagement.lead.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadmanagement.infrastructure.config.AuditingConfig;
import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.outbox.OutboxPublisher;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.lead.api.CreateLeadRequest;
import com.leadmanagement.lead.domain.Lead;
import com.leadmanagement.pipeline.application.PipelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 acceptance test — the full stage-transition path against real
 * Postgres: lead enters the default pipeline's first stage, moves through it,
 * and every move writes the STAGE_CHANGED activity AND the outbox event in
 * the same transaction (OutboxPublisher is imported precisely to prove that).
 *
 * What is verified:
 * 1. createLead() now lands in the REAL default pipeline + first stage
 *    (the Phase 1 placeholder UUIDs are gone).
 * 2. changeStage() updates the row, records STAGE_CHANGED, and writes
 *    LeadStageChangedEvent to the outbox — raw SQL proof for all three.
 * 3. A terminal move derives WON from probability and locks the lead.
 * 4. A stage from a DIFFERENT pipeline is rejected.
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class,
    PipelineService.class, LeadService.class, OutboxPublisher.class,
    LeadStageChangeDockerComposeTest.TestBeans.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeadStageChangeDockerComposeTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private LeadService leadService;

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenant;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        // leads.tenant_id has a real FK to tenants — the tenant must exist
        // (same insertTenant pattern as TenantIsolationJpaDockerComposeTest).
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, status, subscription_tier, max_leads, created_at, updated_at)
                VALUES (?, ?, ?, 'TRIAL', 'STARTER', 1000, NOW(), NOW())
            """, tenant, "Stage Test Tenant", "stage-test-" + tenant);
        TenantContext.setCurrentTenantId(tenant);
        pipelineService.createDefaultPipeline();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM lead_activities WHERE tenant_id = ?", tenant);
        jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenant);
        jdbcTemplate.update("DELETE FROM leads WHERE tenant_id = ?", tenant);
        jdbcTemplate.update("DELETE FROM pipeline_stages WHERE tenant_id = ?", tenant);
        jdbcTemplate.update("DELETE FROM pipelines WHERE tenant_id = ?", tenant);
        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", tenant);
        TenantContext.clear();
    }

    @Test
    void leadMovesThroughRealPipelineWithActivityAndOutboxProof() {
        Lead lead = leadService.createLead(request("journey@test.com"));

        // 1) The lead really entered the default pipeline's first stage.
        PipelineService.PipelineEntryPoint entry = pipelineService.defaultEntryPoint();
        assertThat(lead.getPipelineId()).isEqualTo(entry.pipelineId());
        assertThat(lead.getCurrentStageId()).isEqualTo(entry.firstStageId());

        // 2) Move to Qualified.
        UUID qualifiedId = stageIdByName("Qualified");
        leadService.changeStage(lead.getId(), qualifiedId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_stage_id FROM leads WHERE id = ?", UUID.class, lead.getId()))
            .isEqualTo(qualifiedId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM leads WHERE id = ?", String.class, lead.getId()))
            .as("non-terminal move leaves the status axis untouched")
            .isEqualTo("NEW");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM lead_activities WHERE lead_id = ? AND activity_type = 'STAGE_CHANGED'",
            Integer.class, lead.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'LeadStageChangedEvent' AND tenant_id = ?",
            Integer.class, lead.getId(), tenant))
            .as("outbox write must share the stage-change transaction")
            .isEqualTo(1);

        // 3) A stage from a DIFFERENT pipeline is rejected.
        pipelineService.createPipeline("Enterprise",
            List.of(new PipelineService.NewStage("Discovery", 20, false)));
        UUID foreignStage = stageIdByName("Discovery");
        assertThatThrownBy(() -> leadService.changeStage(lead.getId(), foreignStage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("different pipeline");

        // 4) Terminal move: Won (probability 100) derives status WON...
        leadService.changeStage(lead.getId(), stageIdByName("Won"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM leads WHERE id = ?", String.class, lead.getId()))
            .isEqualTo("WON");

        // ...and locks the lead against further moves.
        assertThatThrownBy(() -> leadService.changeStage(lead.getId(), qualifiedId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("terminal");

        System.out.println("✅ Stage transitions proven: entry → Qualified → WON, activity + outbox written");
    }

    private UUID stageIdByName(String name) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM pipeline_stages WHERE tenant_id = ? AND name = ?", UUID.class, tenant, name);
    }

    private CreateLeadRequest request(String email) {
        return new CreateLeadRequest(email, null, "Stage", "Tester",
            null, null, null, null, null, null);
    }
}
