-- Phase 7: in-app notifications.
--
-- Notifications for lead events are created by the OUTBOX delivery path
-- (OutboxEventHandler -> LeadNotificationSink), which is at-least-once: a
-- crash after the notification insert but before markProcessed commits means
-- the event is re-delivered. The partial unique index on source_event_id is
-- the consumer-side idempotency that turns at-least-once delivery into an
-- exactly-once EFFECT — the Phase 5 reserve-first lesson, seen from the
-- consumer's side of the queue.
--
-- source_event_id is NULL for notifications that don't originate from an
-- outbox event (e.g. the registration welcome), hence the partial index.

CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    type            VARCHAR(50)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    message         TEXT         NOT NULL,
    source_event_id UUID,
    correlation_id  VARCHAR(36),
    read_at         TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    updated_by      VARCHAR(100),
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_notifications_tenant_created ON notifications(tenant_id, created_at DESC);

CREATE UNIQUE INDEX uq_notifications_source_event
    ON notifications(source_event_id)
    WHERE source_event_id IS NOT NULL;
