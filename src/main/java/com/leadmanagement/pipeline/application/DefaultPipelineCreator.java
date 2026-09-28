package com.leadmanagement.pipeline.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.tenant.domain.events.TenantRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the default pipeline for every newly registered tenant.
 *
 * Cross-module wiring per the Modulith rule: the user/tenant side publishes
 * TenantRegisteredEvent; this module reacts. No direct call from AuthService
 * into pipeline code.
 *
 * Tenant context discipline (the Phase 3/4/5 lesson applied proactively):
 * the event carries its tenantId, and this listener sets TenantContext
 * ITSELF around its own scope — it does not trust whatever the publishing
 * thread happened to have, and it restores the previous value afterwards
 * because the publisher (AuthService.register) still needs its own context
 * for the rest of the registration flow.
 *
 * Synchronous on purpose: if pipeline creation fails, registration fails
 * loudly (HTTP 5xx) rather than minting a tenant whose lead creation is
 * broken. The event fires only after tenant + admin user are committed, so
 * the same orphaned-tenant trade-off documented in AuthService applies.
 * PipelineService.createDefaultPipeline() is idempotent, so a client retry
 * of registration-adjacent flows can never double-create.
 */
@Component
public class DefaultPipelineCreator {

    private static final Logger log = LoggerFactory.getLogger(DefaultPipelineCreator.class);

    private final PipelineService pipelineService;

    public DefaultPipelineCreator(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @EventListener
    public void onTenantRegistered(TenantRegisteredEvent event) {
        log.info("Creating default pipeline for newly registered tenant {} ({})",
            event.tenantSlug(), event.tenantId());

        var previous = TenantContext.getCurrentTenantId();
        TenantContext.setCurrentTenantId(event.tenantId());
        try {
            pipelineService.createDefaultPipeline();
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenantId(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
