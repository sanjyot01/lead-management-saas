-- Phase 2: add optimistic locking version column for users

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT;

UPDATE users
SET version = 0
WHERE version IS NULL;

ALTER TABLE users
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE users
    ALTER COLUMN version SET DEFAULT 0;

COMMENT ON COLUMN users.version IS 'Optimistic locking version managed by Hibernate @Version';

