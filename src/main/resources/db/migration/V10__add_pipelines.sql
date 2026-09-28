-- Phase 6: pipeline management.
--
-- pipelines follows the BaseEntity shape (audit columns, soft delete,
-- optimistic locking) — it is mutable tenant business data.
-- pipeline_stages follows the spec's leaner shape (ORIGINAL_PROMPT "Pipeline
-- Stage"): stages are created with their pipeline and effectively immutable
-- rows, so no version/soft-delete machinery.
--
-- leads.pipeline_id / leads.current_stage_id deliberately get NO foreign key
-- here: dev rows created before Phase 6 carry placeholder UUIDs, and the
-- lead's pipeline reference is validated in the service layer (which can give
-- a 4xx instead of a raw constraint error).

CREATE TABLE pipelines (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    created_by  VARCHAR(100) NOT NULL,
    updated_by  VARCHAR(100),
    deleted_at  TIMESTAMP
);

CREATE INDEX idx_pipelines_tenant_id ON pipelines(tenant_id);

-- Exactly one ACTIVE default pipeline per tenant. Partial index so a
-- soft-deleted former default doesn't block promoting a replacement.
CREATE UNIQUE INDEX uq_pipelines_tenant_default
    ON pipelines(tenant_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;

CREATE TABLE pipeline_stages (
    id           UUID PRIMARY KEY,
    pipeline_id  UUID         NOT NULL REFERENCES pipelines(id),
    tenant_id    UUID         NOT NULL,
    name         VARCHAR(255) NOT NULL,
    stage_order  INTEGER      NOT NULL,
    probability  INTEGER      NOT NULL CHECK (probability BETWEEN 0 AND 100),
    is_terminal  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uq_pipeline_stage_order UNIQUE (pipeline_id, stage_order)
);

CREATE INDEX idx_pipeline_stages_tenant_id ON pipeline_stages(tenant_id);
CREATE INDEX idx_pipeline_stages_pipeline_id ON pipeline_stages(pipeline_id);