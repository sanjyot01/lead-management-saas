package com.leadmanagement.pipeline.application;

import com.leadmanagement.infrastructure.config.AuditingConfig;
import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.pipeline.domain.Pipeline;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 acceptance test — default pipeline creation + tenant isolation,
 * against real Postgres (compose pattern, see OutboxProcessorDockerComposeTest
 * for why not Testcontainers and why NOT_SUPPORTED + manual cleanup).
 *
 * What is verified:
 * 1. createDefaultPipeline() creates the pipeline + the full 7-stage funnel
 *    in order, with the documented probabilities and terminal flags.
 * 2. It is idempotent — a second call returns the SAME pipeline, no dupes.
 * 3. defaultEntryPoint() resolves (default pipeline, first stage).
 * 4. Tenant isolation: tenant B sees neither tenant A's pipeline nor its
 *    stages, and B's defaultEntryPoint() fails loudly instead of borrowing A's.
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class, PipelineService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineServiceDockerComposeTest {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM pipeline_stages WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbcTemplate.update("DELETE FROM pipelines WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        TenantContext.clear();
    }

    @Test
    void defaultPipelineCreatedWithOrderedFunnelAndIdempotent() {
        TenantContext.setCurrentTenantId(tenantA);
        Pipeline pipeline = pipelineService.createDefaultPipeline();

        // Raw SQL proof: 7 stages, correct order, correct terminal flags.
        List<Map<String, Object>> stages = jdbcTemplate.queryForList(
            "SELECT name, stage_order, probability, is_terminal FROM pipeline_stages "
                + "WHERE pipeline_id = ? ORDER BY stage_order", pipeline.getId());

        assertThat(stages).hasSize(7);
        assertThat(stages.get(0)).containsEntry("name", "New").containsEntry("probability", 10);
        assertThat(stages.get(5)).containsEntry("name", "Won")
            .containsEntry("probability", 100).containsEntry("is_terminal", true);
        assertThat(stages.get(6)).containsEntry("name", "Lost")
            .containsEntry("probability", 0).containsEntry("is_terminal", true);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_default FROM pipelines WHERE id = ?", Boolean.class, pipeline.getId())).isTrue();

        // Idempotent: second call returns the same pipeline, stage count unchanged.
        Pipeline again = pipelineService.createDefaultPipeline();
        assertThat(again.getId()).isEqualTo(pipeline.getId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pipeline_stages WHERE tenant_id = ?", Integer.class, tenantA))
            .isEqualTo(7);

        // Entry point = (default pipeline, first stage).
        PipelineService.PipelineEntryPoint entry = pipelineService.defaultEntryPoint();
        assertThat(entry.pipelineId()).isEqualTo(pipeline.getId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM pipeline_stages WHERE id = ?", String.class, entry.firstStageId()))
            .isEqualTo("New");

        System.out.println("✅ Default pipeline proven: 7 ordered stages, idempotent, entry=" + entry);
    }

    @Test
    void pipelinesAreTenantIsolated() {
        TenantContext.setCurrentTenantId(tenantA);
        Pipeline pipelineA = pipelineService.createDefaultPipeline();
        UUID stageOfA = pipelineService.defaultEntryPoint().firstStageId();

        TenantContext.setCurrentTenantId(tenantB);

        // B has no default pipeline — loud failure, never A's pipeline.
        assertThatThrownBy(() -> pipelineService.defaultEntryPoint())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no default pipeline");

        // B cannot resolve A's stage by ID (auto tenant filter).
        assertThat(pipelineService.findStage(stageOfA)).isEmpty();

        // B's pipeline listing does not contain A's pipeline.
        assertThat(pipelineService.listPipelines())
            .extracting(Pipeline::getId)
            .doesNotContain(pipelineA.getId());

        System.out.println("✅ Pipeline tenant isolation proven: B sees nothing of A's");
    }
}
