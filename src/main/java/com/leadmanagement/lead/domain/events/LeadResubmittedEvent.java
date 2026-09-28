package com.leadmanagement.lead.domain.events;

import java.util.Map;
import java.util.UUID;

/**
 * Published when a lead is submitted again with the same email (duplicate).
 * Triggers: LeadActivity(FORM_RESUBMITTED) creation, Redis score bump.
 */
public record LeadResubmittedEvent(
    UUID leadId,
    UUID tenantId,
    String email,
    Map<String, Object> newPayload,
    String correlationId
) {}

