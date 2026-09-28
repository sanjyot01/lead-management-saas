-- Phase 5: request idempotency (X-Idempotency-Key header).
--
-- V3 already scaffolded an idempotency_keys table back in Phase 0/1 —
-- nothing has ever written to it. This migration reconciles that scaffold
-- with the Phase 5 entity (IdempotencyRecord) instead of creating a new
-- table:
--   - idempotency_key VARCHAR(36) -> VARCHAR(255): the spec allows
--     client-chosen keys up to 255 chars, not just UUIDs.
--   - status_code -> response_status: matches the spec's column name.
--   - response_body JSONB -> TEXT: the filter stores the captured response
--     verbatim; it is opaque replay data, never queried as JSON.
--
-- Reserve-first design: a row is INSERTed as a claim BEFORE the request is
-- processed, with response_status/response_body NULL meaning "in flight".
-- The UNIQUE (tenant_id, idempotency_key) constraint (uk_idempotency_tenant_key,
-- from V3) is what makes two concurrent retries with the same key safe: the
-- second INSERT fails and the caller is told the request is already being
-- processed. This is the same lesson as the outbox atomic claim (Phase 4
-- Bug 2), applied to HTTP: check-then-act without a durable claim is a race.
--
-- Postgres (not Redis) on purpose: idempotency is a correctness guarantee,
-- and the fail-open contract used for every Redis feature so far would here
-- mean "duplicates allowed whenever Redis blinks" — the exact failure the
-- feature exists to prevent.

ALTER TABLE idempotency_keys ALTER COLUMN idempotency_key TYPE VARCHAR(255);
ALTER TABLE idempotency_keys RENAME COLUMN status_code TO response_status;
ALTER TABLE idempotency_keys ALTER COLUMN response_body TYPE TEXT USING response_body::text;

-- Redundant with the UNIQUE constraint's backing index on the same columns.
DROP INDEX IF EXISTS idx_idempotency_tenant_key;