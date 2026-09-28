package com.leadmanagement.tenant.domain;

/**
 * Tenant account status.
 *
 * Status Flow:
 * TRIAL → ACTIVE (after payment)
 * ACTIVE → SUSPENDED (payment failure, policy violation)
 * SUSPENDED → ACTIVE (issue resolved)
 *
 * Business Rules:
 * - TRIAL: Limited features, 14-day period
 * - ACTIVE: Full access, paying customer
 * - SUSPENDED: Read-only access, cannot create leads
 */
public enum TenantStatus {
    /**
     * Trial period (14 days, limited features)
     */
    TRIAL,

    /**
     * Active paying customer (full access)
     */
    ACTIVE,

    /**
     * Suspended account (payment failure or policy violation)
     * Read-only access, cannot create new data
     */
    SUSPENDED
}

