-- Exponential backoff for outbox retry processing.
-- Without this, a failing event's status flips straight back to PENDING and
-- gets retried on the very next 500ms poll — hammering a downstream that's
-- already failing, instead of backing off.

ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMP;

-- Existing PENDING rows are immediately eligible (no artificial delay for
-- events that haven't failed yet).
UPDATE outbox_events SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_events ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE outbox_events ALTER COLUMN next_attempt_at SET DEFAULT CURRENT_TIMESTAMP;

-- The poller's WHERE clause now filters on next_attempt_at too; keep it in
-- the same composite index it already queries by.
DROP INDEX IF EXISTS idx_outbox_status_created;
CREATE INDEX idx_outbox_status_next_attempt ON outbox_events(status, next_attempt_at);

COMMENT ON COLUMN outbox_events.next_attempt_at IS 'Event not eligible for polling until this time; pushed forward on each retry (exponential backoff)';
