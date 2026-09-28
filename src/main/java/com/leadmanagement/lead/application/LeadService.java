package com.leadmanagement.lead.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.infrastructure.web.CorrelationIdFilter;
import com.leadmanagement.lead.api.CreateLeadRequest;
import com.leadmanagement.lead.domain.Lead;
import com.leadmanagement.lead.domain.LeadActivity;
import com.leadmanagement.lead.domain.LeadActivityType;
import com.leadmanagement.lead.domain.LeadStatus;
import com.leadmanagement.lead.domain.events.LeadCreatedEvent;
import com.leadmanagement.lead.domain.events.LeadResubmittedEvent;
import com.leadmanagement.lead.domain.events.LeadStageChangedEvent;
import com.leadmanagement.lead.infrastructure.LeadActivityRepository;
import com.leadmanagement.lead.infrastructure.LeadRepository;
import com.leadmanagement.pipeline.application.PipelineService;
import com.leadmanagement.pipeline.domain.PipelineStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for lead management operations.
 *
 * Handles lead creation with deduplication logic.
 * Publishes domain events which the OutboxPublisher writes to outbox in the same transaction.
 */
@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository leadRepository;
    private final LeadActivityRepository leadActivityRepository;
    private final PipelineService pipelineService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Counter createLeadRequests;
    private final Counter createLeadErrors;
    private final Counter createLeadDuplicates;
    private final Counter createLeadAccessDenied;
    private final Counter stageChanges;
    private final Timer createLeadDuration;

    public LeadService(LeadRepository leadRepository,
                       LeadActivityRepository leadActivityRepository,
                       PipelineService pipelineService,
                       ApplicationEventPublisher eventPublisher,
                       MeterRegistry meterRegistry) {
        this.leadRepository = leadRepository;
        this.leadActivityRepository = leadActivityRepository;
        this.pipelineService = pipelineService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.createLeadRequests = meterRegistry.counter("lead.create.requests.total");
        this.createLeadErrors = meterRegistry.counter("lead.create.errors.total");
        this.createLeadDuplicates = meterRegistry.counter("lead.create.duplicates.total");
        this.createLeadAccessDenied = meterRegistry.counter("lead.create.access_denied.total");
        this.stageChanges = meterRegistry.counter("lead.stage.changed.total");
        this.createLeadDuration = meterRegistry.timer("lead.create.duration");
    }

    /**
     * Create a new lead or record a resubmission if duplicate email found.
     *
     * Business Rules:
     * - If email already exists for this tenant → create activity, return existing lead
     * - If email is new → create new lead with status NEW
     * Publishes domain events captured by OutboxPublisher in the same transaction.
     *
     * @param request the lead creation request
     * @return the lead (new or existing)
     */
    @Transactional
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public Lead createLead(CreateLeadRequest request) {
        createLeadRequests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        log.debug("Creating lead with email: {}", request.email());
        String correlationId = CorrelationIdFilter.current();

        try {
            Optional<Lead> existingLead = leadRepository.findByEmail(request.email());

            if (existingLead.isPresent()) {
                ensureRepCanAccess(existingLead.get());
                createLeadDuplicates.increment();
                log.info("Duplicate lead detected for email: {}", request.email());
                return handleDuplicateLead(existingLead.get(), request, correlationId);
            }

            // New leads enter the tenant's default pipeline at its first
            // stage (created at registration by DefaultPipelineCreator).
            PipelineService.PipelineEntryPoint entry = pipelineService.defaultEntryPoint();

            Lead lead = new Lead(request.email(), entry.pipelineId(), entry.firstStageId(), request.resolvedSource());
            lead.setFirstName(request.resolvedFirstName());
            lead.setLastName(request.resolvedLastName());
            lead.setCompany(request.company());
            lead.setPhone(request.phone());
            lead.setTitle(request.title());
            lead.setCustomFields(request.customFields());
            lead.setStatus(LeadStatus.NEW);

            // Store rawJsonPayload in sourceDetails if provided
            if (request.rawJsonPayload() != null && !request.rawJsonPayload().isBlank()) {
                Map<String, Object> sourceDetails = new HashMap<>();
                sourceDetails.put("rawPayload", request.rawJsonPayload());
                lead.setSourceDetails(sourceDetails);
            }

            Lead savedLead = leadRepository.save(lead);
            log.info("Created new lead id={} email={}", savedLead.getId(), savedLead.getEmail());

            eventPublisher.publishEvent(new LeadCreatedEvent(
                savedLead.getId(),
                TenantContext.getCurrentTenantId(),
                savedLead.getEmail(),
                savedLead.getSource(),
                correlationId
            ));

            return savedLead;
        } catch (AccessDeniedException ex) {
            createLeadAccessDenied.increment();
            throw ex;
        } catch (RuntimeException ex) {
            createLeadErrors.increment();
            throw ex;
        } finally {
            sample.stop(createLeadDuration);
        }
    }

    /**
     * Moves a lead to a different stage of its own pipeline.
     *
     * Rules:
     * - The target stage must exist for this tenant (auto tenant-scoped
     *   lookup) AND belong to the lead's own pipeline — a stage ID from a
     *   different pipeline is a client error, not a crash.
     * - A lead in a terminal status (WON/LOST) cannot move again.
     * - Moving to a terminal stage derives the status: probability > 0 → WON,
     *   probability = 0 → LOST. Non-terminal moves leave the status axis
     *   untouched (status and stage are related but independent dimensions).
     * - Records a STAGE_CHANGED activity and publishes LeadStageChangedEvent
     *   in the SAME transaction, so the outbox write shares the commit.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public Lead changeStage(UUID leadId, UUID targetStageId) {
        Lead lead = leadRepository.findById(leadId)
            .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        ensureRepCanAccess(lead);

        if (lead.isTerminal()) {
            throw new IllegalArgumentException(
                "Lead is in terminal status " + lead.getStatus() + " and cannot change stage");
        }

        PipelineStage target = pipelineService.findStage(targetStageId)
            .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + targetStageId));
        if (!target.getPipelineId().equals(lead.getPipelineId())) {
            throw new IllegalArgumentException(
                "Stage " + targetStageId + " belongs to a different pipeline than the lead");
        }
        if (target.getId().equals(lead.getCurrentStageId())) {
            return lead; // no-op move, nothing to record
        }

        UUID oldStageId = lead.getCurrentStageId();
        LeadStatus newStatus = target.isTerminal()
            ? (target.getProbability() > 0 ? LeadStatus.WON : LeadStatus.LOST)
            : lead.getStatus();
        lead.updateStage(target.getId(), newStatus);

        Map<String, Object> payload = new HashMap<>();
        payload.put("old_stage_id", oldStageId.toString());
        payload.put("new_stage_id", target.getId().toString());
        payload.put("new_stage_name", target.getName());
        payload.put("new_status", newStatus.toString());
        leadActivityRepository.save(new LeadActivity(lead.getId(), LeadActivityType.STAGE_CHANGED, payload));

        String correlationId = CorrelationIdFilter.current();
        eventPublisher.publishEvent(new LeadStageChangedEvent(
            lead.getId(),
            TenantContext.getCurrentTenantId(),
            oldStageId,
            target.getId(),
            target.getName(),
            newStatus,
            correlationId
        ));

        stageChanges.increment();
        log.info("Lead {} moved to stage '{}' (status {})", lead.getId(), target.getName(), newStatus);
        return lead;
    }

    private void ensureRepCanAccess(Lead lead) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return;
        }

        boolean isTenantAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_TENANT_ADMIN".equals(a.getAuthority()));
        if (isTenantAdmin) {
            return;
        }

        boolean isRep = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_REP".equals(a.getAuthority()));
        if (!isRep || lead.getAssignedTo() == null) {
            return;
        }

        try {
            UUID principalUserId = UUID.fromString(authentication.getName());
            if (!principalUserId.equals(lead.getAssignedTo())) {
                throw new AccessDeniedException("ROLE_REP can only access assigned leads");
            }
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("ROLE_REP principal is invalid for assignment checks");
        }
    }

    private Lead handleDuplicateLead(Lead existingLead, CreateLeadRequest request, String correlationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", request.email());
        payload.put("firstName", request.resolvedFirstName());
        payload.put("lastName", request.resolvedLastName());
        payload.put("company", request.company());
        payload.put("source", request.resolvedSource().toString());
        payload.put("customFields", request.customFields());
        if (request.rawJsonPayload() != null) {
            payload.put("rawPayload", request.rawJsonPayload());
        }

        LeadActivity activity = new LeadActivity(existingLead.getId(), LeadActivityType.FORM_RESUBMITTED, payload);
        leadActivityRepository.save(activity);
        log.info("Recorded FORM_RESUBMITTED activity for lead id={}", existingLead.getId());

        eventPublisher.publishEvent(new LeadResubmittedEvent(
            existingLead.getId(),
            TenantContext.getCurrentTenantId(),
            existingLead.getEmail(),
            payload,
            correlationId
        ));

        return existingLead;
    }
}
