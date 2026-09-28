-- Transactional Outbox Pattern table
-- Events written here in same transaction as domain changes, then polled and published by background worker

-- Spring Modulith Event Publication table
-- Used by Spring Modulith for event publishing tracking
CREATE TABLE event_publication (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP
);

CREATE INDEX idx_event_publication_by_completion_date ON event_publication(completion_date);

-- Event Publication Archive (completed events)
CREATE TABLE event_publication_archive (
    id UUID PRIMARY KEY,
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP NOT NULL
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    correlation_id VARCHAR(36),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
);

-- Indexes optimized for the background poller query:
--   SELECT ... WHERE status = 'PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at);
CREATE INDEX idx_outbox_tenant_id ON outbox_events(tenant_id);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_correlation_id ON outbox_events(correlation_id);

COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable domain event publishing';
COMMENT ON COLUMN outbox_events.status IS 'PENDING -> PROCESSING -> PROCESSED or FAILED';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of processing attempts (max 5)';
COMMENT ON COLUMN outbox_events.payload IS 'Serialized event payload (JSONB)';

