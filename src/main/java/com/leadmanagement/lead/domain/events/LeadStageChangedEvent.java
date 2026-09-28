package com.leadmanagement.lead.domain.events;

import com.leadmanagement.lead.domain.LeadStatus;

import java.util.UUID;

/**
 * Published when a lead moves to a different pipeline stage.
 * Triggers: outbox write (delivered by the outbox processor), notification check (Phase 7).
 */
public record LeadStageChangedEvent(
    UUID leadId,
    UUID tenantId,
    UUID oldStageId,
    UUID newStageId,
    String newStageName,
    LeadStatus newStatus,
    String correlationId
) {}
