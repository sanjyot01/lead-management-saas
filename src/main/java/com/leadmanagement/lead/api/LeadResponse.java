package com.leadmanagement.lead.api;

import com.leadmanagement.lead.domain.Lead;
import com.leadmanagement.lead.domain.LeadSource;
import com.leadmanagement.lead.domain.LeadStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for Lead.
 *
 * Converts Lead entity to API response format.
 * Hides internal fields like tenantId (client shouldn't see it).
 */
public record LeadResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String company,
    String phone,
    String title,
    UUID pipelineId,
    UUID currentStageId,
    LeadStatus status,
    LeadSource source,
    Integer score,
    Map<String, Object> customFields,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * Convert Lead entity to response DTO.
     */
    public static LeadResponse fromEntity(Lead lead) {
        return new LeadResponse(
            lead.getId(),
            lead.getEmail(),
            lead.getFirstName(),
            lead.getLastName(),
            lead.getCompany(),
            lead.getPhone(),
            lead.getTitle(),
            lead.getPipelineId(),
            lead.getCurrentStageId(),
            lead.getStatus(),
            lead.getSource(),
            lead.getScore(),
            lead.getCustomFields(),
            lead.getCreatedAt(),
            lead.getUpdatedAt()
        );
    }
}

