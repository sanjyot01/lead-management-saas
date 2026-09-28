package com.leadmanagement.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadmanagement.lead.domain.events.LeadCreatedEvent;
import com.leadmanagement.lead.domain.events.LeadResubmittedEvent;
import com.leadmanagement.lead.domain.events.LeadStageChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Listens to domain events and writes them to the outbox table
 * in the SAME transaction as the domain operation.
 *
 * This is the key guarantee of the Transactional Outbox Pattern:
 * if the business transaction commits, the event is guaranteed to be in the DB.
 * If it rolls back, the event is never written.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes LeadCreatedEvent to outbox within the same transaction.
     * Uses MANDATORY propagation to enforce that this runs inside an existing transaction.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLeadCreated(LeadCreatedEvent event) {
        log.debug("Writing LeadCreatedEvent to outbox for lead: {}", event.leadId());

        Map<String, Object> payload = Map.of(
            "leadId", event.leadId().toString(),
            "tenantId", event.tenantId().toString(),
            "email", event.email(),
            "source", event.source().toString(),
            "correlationId", event.correlationId() != null ? event.correlationId() : ""
        );

        OutboxEvent outboxEvent = new OutboxEvent(
            "Lead",
            event.leadId(),
            "LeadCreatedEvent",
            payload,
            event.correlationId()
        );

        outboxEventRepository.save(outboxEvent);
        log.debug("LeadCreatedEvent written to outbox: {}", outboxEvent.getId());
    }

    /**
     * Writes LeadResubmittedEvent to outbox within the same transaction.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLeadResubmitted(LeadResubmittedEvent event) {
        log.debug("Writing LeadResubmittedEvent to outbox for lead: {}", event.leadId());

        Map<String, Object> payload = Map.of(
            "leadId", event.leadId().toString(),
            "tenantId", event.tenantId().toString(),
            "email", event.email(),
            "correlationId", event.correlationId() != null ? event.correlationId() : ""
        );

        OutboxEvent outboxEvent = new OutboxEvent(
            "Lead",
            event.leadId(),
            "LeadResubmittedEvent",
            payload,
            event.correlationId()
        );

        outboxEventRepository.save(outboxEvent);
        log.debug("LeadResubmittedEvent written to outbox: {}", outboxEvent.getId());
    }

    /**
     * Writes LeadStageChangedEvent to outbox within the same transaction
     * as the stage move + STAGE_CHANGED activity.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLeadStageChanged(LeadStageChangedEvent event) {
        log.debug("Writing LeadStageChangedEvent to outbox for lead: {}", event.leadId());

        Map<String, Object> payload = Map.of(
            "leadId", event.leadId().toString(),
            "tenantId", event.tenantId().toString(),
            "oldStageId", event.oldStageId().toString(),
            "newStageId", event.newStageId().toString(),
            "newStageName", event.newStageName(),
            "newStatus", event.newStatus().toString(),
            "correlationId", event.correlationId() != null ? event.correlationId() : ""
        );

        OutboxEvent outboxEvent = new OutboxEvent(
            "Lead",
            event.leadId(),
            "LeadStageChangedEvent",
            payload,
            event.correlationId()
        );

        outboxEventRepository.save(outboxEvent);
        log.debug("LeadStageChangedEvent written to outbox: {}", outboxEvent.getId());
    }
}

