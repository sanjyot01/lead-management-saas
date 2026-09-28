package com.leadmanagement.infrastructure.config;

import com.leadmanagement.infrastructure.security.TenantContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hibernate 6 TenantId resolver for @TenantId annotation support.
 *
 * Tells Hibernate what value to use for tenant filtering.
 * Reads from TenantContext which is set by JwtAuthenticationFilter (request threads)
 * or by background workers such as OutboxProcessor (their own threads).
 *
 * When @TenantId annotation is present on BaseEntity, Hibernate will automatically:
 * - Inject this value when inserting new entities
 * - Add WHERE tenant_id = :currentTenantId to all queries
 *
 * IMPORTANT: This bean is auto-discovered by Spring Boot's Hibernate auto-configuration.
 */
@Component
public class CurrentTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    private static final Logger log = LoggerFactory.getLogger(CurrentTenantResolver.class);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        log.trace("Resolved current tenant ID: {}", tenantId);
        return tenantId;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        // Not using multi-level tenancy, so no root tenant concept
        return false;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
