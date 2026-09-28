package com.leadmanagement.infrastructure.security;

import java.util.UUID;

/**
 * ThreadLocal storage for the current tenant ID.
 *
 * Set by JwtAuthenticationFilter on each authenticated request (and by
 * background workers such as OutboxProcessor for their own threads).
 * Read by Hibernate TenantIdResolver to inject tenant_id into queries.
 *
 * IMPORTANT: Must be cleared after each request to prevent thread pool pollution.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // Utility class - no instantiation
    }

    /**
     * Sets the current tenant ID for this request thread.
     *
     * @param tenantId the tenant ID (from the validated JWT)
     */
    public static void setCurrentTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Gets the current tenant ID for this request thread.
     *
     * @return the tenant ID, or null if not set
     */
    public static UUID getCurrentTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * Clears the tenant context for this thread.
     * MUST be called in a finally block after each request.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Checks if a tenant context is currently set.
     *
     * @return true if tenant ID is set
     */
    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }
}

