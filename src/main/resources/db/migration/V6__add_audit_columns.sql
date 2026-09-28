-- Add identity/auditability columns for Spring Data JPA Auditing
-- created_by: immutable creator principal
-- updated_by: last modifier principal

ALTER TABLE users ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

ALTER TABLE leads ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

ALTER TABLE lead_activities ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE lead_activities ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Backfill existing rows to preserve NOT NULL contract for created_by
UPDATE users SET created_by = 'system' WHERE created_by IS NULL;
UPDATE leads SET created_by = 'system' WHERE created_by IS NULL;
UPDATE lead_activities SET created_by = 'system' WHERE created_by IS NULL;

ALTER TABLE users ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE leads ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE lead_activities ALTER COLUMN created_by SET NOT NULL;

COMMENT ON COLUMN users.created_by IS 'Principal that created this row (@CreatedBy)';
COMMENT ON COLUMN users.updated_by IS 'Principal that last updated this row (@LastModifiedBy)';
COMMENT ON COLUMN leads.created_by IS 'Principal that created this row (@CreatedBy)';
COMMENT ON COLUMN leads.updated_by IS 'Principal that last updated this row (@LastModifiedBy)';
COMMENT ON COLUMN lead_activities.created_by IS 'Principal that created this row (@CreatedBy)';
COMMENT ON COLUMN lead_activities.updated_by IS 'Principal that last updated this row (@LastModifiedBy)';

