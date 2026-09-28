-- Performance indexes and idempotency key table

CREATE INDEX idx_leads_custom_fields_gin ON leads USING GIN(custom_fields);
CREATE INDEX idx_leads_source_details_gin ON leads USING GIN(source_details);
CREATE INDEX idx_outbox_payload_gin ON outbox_events USING GIN(payload);
CREATE INDEX idx_lead_activities_payload_gin ON lead_activities USING GIN(payload);
CREATE INDEX idx_leads_tenant_status_score ON leads(tenant_id, status, score DESC);
CREATE INDEX idx_leads_tenant_assigned ON leads(tenant_id, assigned_to);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body JSONB,
    status_code INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    CONSTRAINT uk_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_tenant_key ON idempotency_keys(tenant_id, idempotency_key);
CREATE INDEX idx_idempotency_expires_at ON idempotency_keys(expires_at);

