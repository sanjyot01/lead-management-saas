-- Lead Management SaaS - Initial Schema
-- Multi-tenant database with row-level isolation via tenant_id

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Tenants table (organizations using the SaaS platform)
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
    subscription_tier VARCHAR(50),
    max_leads INTEGER,
    api_key_hash VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for tenants table
CREATE INDEX idx_tenants_slug ON tenants(slug);
CREATE INDEX idx_tenants_status ON tenants(status);

-- Users table (users within each tenant)
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,

    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_users_tenant_email UNIQUE (tenant_id, email)
);

-- Indexes for users table
CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);

-- Leads table
CREATE TABLE leads (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    company VARCHAR(255),
    title VARCHAR(100),
    pipeline_id UUID NOT NULL,
    current_stage_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'NEW',
    source VARCHAR(50) NOT NULL DEFAULT 'MANUAL',
    source_details JSONB,
    score INTEGER DEFAULT 0,
    assigned_to UUID,
    custom_fields JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,

    CONSTRAINT fk_leads_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uk_leads_tenant_email UNIQUE (tenant_id, email)
);

-- Indexes for leads table
CREATE INDEX idx_leads_tenant_id ON leads(tenant_id);
CREATE INDEX idx_leads_email ON leads(email);
CREATE INDEX idx_leads_status ON leads(status);
CREATE INDEX idx_leads_pipeline_id ON leads(pipeline_id);
CREATE INDEX idx_leads_current_stage_id ON leads(current_stage_id);
CREATE INDEX idx_leads_assigned_to ON leads(assigned_to);
CREATE INDEX idx_leads_created_at ON leads(created_at);
CREATE INDEX idx_leads_score ON leads(score);

-- Lead activities table
CREATE TABLE lead_activities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id UUID NOT NULL,
    lead_id UUID NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    payload JSONB,
    performed_by UUID,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,

    CONSTRAINT fk_lead_activities_lead FOREIGN KEY (lead_id) REFERENCES leads(id) ON DELETE CASCADE
);

-- Indexes for lead_activities table
CREATE INDEX idx_lead_activities_tenant_id ON lead_activities(tenant_id);
CREATE INDEX idx_lead_activities_lead_id ON lead_activities(lead_id);
CREATE INDEX idx_lead_activities_type ON lead_activities(activity_type);
CREATE INDEX idx_lead_activities_created_at ON lead_activities(created_at);
CREATE INDEX idx_lead_activities_performed_by ON lead_activities(performed_by);

-- Comments
COMMENT ON TABLE tenants IS 'SaaS tenant organizations (customers)';
COMMENT ON COLUMN tenants.slug IS 'URL-friendly identifier (e.g., wayne-enterprises)';
COMMENT ON COLUMN tenants.status IS 'TRIAL, ACTIVE, or SUSPENDED';

COMMENT ON TABLE users IS 'Users within each tenant organization';
COMMENT ON COLUMN users.tenant_id IS 'Tenant identifier for multi-tenant isolation';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hashed password (never store plaintext)';
COMMENT ON COLUMN users.role IS 'ADMIN, MEMBER, or VIEWER';

COMMENT ON TABLE leads IS 'Main leads table with multi-tenant isolation via tenant_id';
COMMENT ON COLUMN leads.tenant_id IS 'Tenant identifier for row-level isolation (automatically set by Hibernate @TenantId)';
COMMENT ON COLUMN leads.source_details IS 'Additional source metadata (JSONB for flexibility)';
COMMENT ON COLUMN leads.custom_fields IS 'Tenant-specific custom fields (JSONB to avoid schema changes)';

COMMENT ON TABLE lead_activities IS 'Activity log for leads (form resubmissions, status changes, etc.)';
COMMENT ON COLUMN lead_activities.payload IS 'Activity-specific data (JSONB for flexibility)';

