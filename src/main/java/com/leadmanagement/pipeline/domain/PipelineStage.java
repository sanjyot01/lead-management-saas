package com.leadmanagement.pipeline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stage of a pipeline.
 *
 * Deliberately NOT a BaseEntity subclass — the spec's shape (ORIGINAL_PROMPT
 * "Pipeline Stage") is leaner: stages are created with their pipeline and are
 * effectively immutable rows, so no optimistic locking, soft delete, or audit
 * columns. Standalone @TenantId entity like OutboxEvent/IdempotencyRecord.
 */
@Entity
@Table(
    name = "pipeline_stages",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_pipeline_stage_order",
        columnNames = {"pipeline_id", "stage_order"}
    ),
    indexes = {
        @Index(name = "idx_pipeline_stages_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_pipeline_stages_pipeline_id", columnList = "pipeline_id")
    }
)
public class PipelineStage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pipeline_id", nullable = false, updatable = false)
    private UUID pipelineId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    /** Win probability 0-100 a lead in this stage represents. */
    @Column(name = "probability", nullable = false)
    private int probability;

    /** Terminal stages (Won/Lost) end a lead's journey through the pipeline. */
    @Column(name = "is_terminal", nullable = false)
    private boolean terminal = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PipelineStage() {}

    public PipelineStage(UUID pipelineId, String name, int stageOrder, int probability, boolean terminal) {
        this.pipelineId = pipelineId;
        this.name = name;
        this.stageOrder = stageOrder;
        this.probability = probability;
        this.terminal = terminal;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getPipelineId() { return pipelineId; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public int getStageOrder() { return stageOrder; }
    public int getProbability() { return probability; }
    public boolean isTerminal() { return terminal; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}