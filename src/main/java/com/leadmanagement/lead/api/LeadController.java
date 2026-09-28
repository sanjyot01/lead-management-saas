package com.leadmanagement.lead.api;

import com.leadmanagement.lead.application.LeadService;
import com.leadmanagement.lead.domain.Lead;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API controller for lead management.
 *
 * All endpoints automatically scoped to the tenant from the JWT (via TenantContext).
 */
@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private static final Logger log = LoggerFactory.getLogger(LeadController.class);

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    /**
     * Create a new lead.
     *
     * If a lead with the same email already exists for this tenant,
     * an activity is recorded and the existing lead is returned.
     *
     * @param request the lead creation request
     * @return 201 Created with lead data
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public ResponseEntity<LeadResponse> createLead(@Valid @RequestBody CreateLeadRequest request) {
        log.info("Received request to create lead: {}", request.email());

        Lead lead = leadService.createLead(request);
        LeadResponse response = LeadResponse.fromEntity(lead);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Move a lead to a different stage of its pipeline.
     *
     * @return 200 with the updated lead
     */
    @PatchMapping("/{id}/stage")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public ResponseEntity<LeadResponse> changeStage(@PathVariable("id") UUID leadId,
                                                    @Valid @RequestBody ChangeStageRequest request) {
        log.info("Received request to move lead {} to stage {}", leadId, request.stageId());

        Lead lead = leadService.changeStage(leadId, request.stageId());
        return ResponseEntity.ok(LeadResponse.fromEntity(lead));
    }
}

