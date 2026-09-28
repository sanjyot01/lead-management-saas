package com.leadmanagement.tenant.domain.events;

import java.util.UUID;

/**
 * Published when a tenant registration has fully succeeded (tenant AND admin
 * user both durably committed). Carries the tenantId explicitly so listeners
 * never have to trust the publishing thread's ambient context.
 *
 * Triggers: default pipeline creation (pipeline module).
 */
public record TenantRegisteredEvent(
    UUID tenantId,
    String tenantSlug,
    String correlationId
) {}
