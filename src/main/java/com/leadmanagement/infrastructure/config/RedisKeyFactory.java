package com.leadmanagement.infrastructure.config;

import com.leadmanagement.infrastructure.security.TenantContext;

import java.util.UUID;

/**
 * Builds tenant-scoped Redis keys from one central place.
 *
 * Hibernate's @TenantId gives Postgres queries automatic tenant filtering;
 * Redis has no equivalent mechanism. Every key must embed the tenant ID by
 * convention, so all key construction is funneled through here — never by
 * string concatenation at call sites — or a single unscoped key becomes a
 * cross-tenant data leak.
 *
 * Key shape: lm:{tenantId}:{...}  ("lm" namespaces this app in a shared Redis)
 */
public final class RedisKeyFactory {

    private static final String PREFIX = "lm";

    private RedisKeyFactory() {
    }

    /** ZSET: member=leadId, score=lead score. */
    public static String leadScoresKey() {
        return PREFIX + ":" + requireTenantId() + ":lead-scores";
    }

    /** STRING (JSON), TTL 5 min. */
    public static String leadSummaryKey(UUID leadId) {
        return PREFIX + ":" + requireTenantId() + ":lead-summary:" + leadId;
    }

    /**
     * Fixed-window rate-limit counter for lead ingestion, scoped per TENANT
     * (not per user) — the spec's limit protects the shared ingestion
     * capacity from a single noisy tenant, and a tenant could otherwise
     * sidestep a per-user budget by spreading requests across API users.
     */
    public static String rateLimitKey() {
        return PREFIX + ":" + requireTenantId() + ":rate:lead-ingestion";
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "Cannot build a Redis key without a tenant context — no context, no data.");
        }
        return tenantId;
    }
}