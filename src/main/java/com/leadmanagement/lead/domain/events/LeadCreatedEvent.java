package com.leadmanagement.lead.domain.events;

import com.leadmanagement.lead.domain.LeadSource;

import java.util.UUID;

/**
 * Published when a new lead is successfully created.
 * Triggers: outbox write, Redis score initialization, notification check.
 */
public record LeadCreatedEvent(
    UUID leadId,
    UUID tenantId,
    String email,
    LeadSource source,
    String correlationId
) {}

