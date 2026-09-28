package com.leadmanagement.lead.domain;

import com.leadmanagement.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lead entity representing a potential customer in the lead management system.
 *
 * Multi-tenant isolated via tenant_id column (inherited from BaseEntity with @TenantId).
 * Supports flexible custom fields stored as JSONB for extensibility without schema changes.
 * Email is unique per tenant (enforced by database constraint).
 *
 * Deduplication strategy:
 * - Unique constraint on (tenant_id, email)
 * - Duplicate submissions create LeadActivity records instead of new leads
 * - Score is incremented in Redis ZSET on resubmission
 */
@Entity
@Table(
    name = "leads",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_leads_tenant_email",
            columnNames = {"tenant_id", "email"}
        )
    },
    indexes = {
        @Index(name = "idx_leads_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_leads_email", columnList = "email"),
        @Index(name = "idx_leads_status", columnList = "status"),
        @Index(name = "idx_leads_pipeline_id", columnList = "pipeline_id"),
        @Index(name = "idx_leads_current_stage_id", columnList = "current_stage_id"),
        @Index(name = "idx_leads_assigned_to", columnList = "assigned_to"),
        @Index(name = "idx_leads_created_at", columnList = "created_at"),
        @Index(name = "idx_leads_score", columnList = "score")
    }
)
public class Lead extends BaseEntity {

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(length = 255)
    private String company;

    @Column(length = 100)
    private String title;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "current_stage_id", nullable = false)
    private UUID currentStageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LeadStatus status = LeadStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LeadSource source = LeadSource.MANUAL;

    /**
     * Additional details about the lead source (e.g., form name, campaign ID, referrer).
     * Stored as JSONB for flexibility.
     * Example: {"form_name": "Contact Us", "utm_campaign": "spring_promo", "referrer": "google.com"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_details", columnDefinition = "jsonb")
    private Map<String, Object> sourceDetails;

    /**
     * Lead score (0-100). Updated by scoring service based on engagement.
     * Stored in both database (persistence) and Redis ZSET (fast ranking queries).
     */
    @Column
    private Integer score = 0;

    /**
     * User ID of the assigned sales representative.
     */
    @Column(name = "assigned_to")
    private UUID assignedTo;

    /**
     * Extensible custom fields defined by tenant.
     * Stored as JSONB to avoid schema migrations for custom field changes.
     * Example: {"industry": "SaaS", "employee_count": 50, "budget": 100000, "timeline": "Q2 2026"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields;

    // Constructors

    /**
     * JPA requires a no-arg constructor.
     */
    protected Lead() {
        this.sourceDetails = new HashMap<>();
        this.customFields = new HashMap<>();
    }

    /**
     * Creates a new lead with required fields.
     *
     * @param email the lead's email address (unique per tenant)
     * @param pipelineId the pipeline this lead belongs to
     * @param currentStageId the initial pipeline stage
     * @param source the source of lead ingestion
     */
    public Lead(String email, UUID pipelineId, UUID currentStageId, LeadSource source) {
        this();
        this.email = email;
        this.pipelineId = pipelineId;
        this.currentStageId = currentStageId;
        this.source = source;
    }

    // Business methods

    /**
     * Updates the lead's pipeline stage and status.
     * Should be called when moving lead through the pipeline.
     *
     * @param newStageId the new pipeline stage ID
     * @param newStatus the new lead status
     * @throws IllegalStateException if lead is in terminal state
     */
    public void updateStage(UUID newStageId, LeadStatus newStatus) {
        if (isTerminal()) {
            throw new IllegalStateException(
                "Cannot update stage for lead in terminal state: " + this.status
            );
        }
        this.currentStageId = newStageId;
        this.status = newStatus;
    }

    /**
     * Assigns the lead to a sales representative.
     *
     * @param userId the user ID to assign (must belong to same tenant)
     */
    public void assignTo(UUID userId) {
        this.assignedTo = userId;
    }

    /**
     * Unassigns the lead from any sales representative.
     */
    public void unassign() {
        this.assignedTo = null;
    }

    /**
     * Updates the lead's score.
     * Score is used for prioritization and should be synced with Redis ZSET.
     *
     * @param newScore the new score (0-100)
     * @throws IllegalArgumentException if score is out of range
     */
    public void updateScore(Integer newScore) {
        if (newScore != null && (newScore < 0 || newScore > 100)) {
            throw new IllegalArgumentException("Lead score must be between 0 and 100, got: " + newScore);
        }
        this.score = newScore;
    }

    /**
     * Increments the lead score by a delta.
     * Useful for engagement-based scoring (form resubmission, email open, etc.).
     *
     * @param delta the amount to add to current score
     */
    public void incrementScore(int delta) {
        int newScore = Math.min(100, Math.max(0, (this.score != null ? this.score : 0) + delta));
        this.score = newScore;
    }

    /**
     * Adds or updates a custom field.
     *
     * @param key the field name
     * @param value the field value
     */
    public void setCustomField(String key, Object value) {
        if (this.customFields == null) {
            this.customFields = new HashMap<>();
        }
        this.customFields.put(key, value);
    }

    /**
     * Gets a custom field value.
     *
     * @param key the field name
     * @return the field value, or null if not found
     */
    public Object getCustomField(String key) {
        return this.customFields != null ? this.customFields.get(key) : null;
    }

    /**
     * Adds or updates a source detail.
     *
     * @param key the detail name
     * @param value the detail value
     */
    public void setSourceDetail(String key, Object value) {
        if (this.sourceDetails == null) {
            this.sourceDetails = new HashMap<>();
        }
        this.sourceDetails.put(key, value);
    }

    /**
     * Checks if the lead is in a terminal state (won or lost).
     * Terminal leads should not be updated except for archival.
     *
     * @return true if lead is won or lost
     */
    public boolean isTerminal() {
        return status == LeadStatus.WON || status == LeadStatus.LOST;
    }

    /**
     * Checks if the lead is archived.
     *
     * @return true if lead is archived
     */
    public boolean isArchived() {
        return status == LeadStatus.ARCHIVED;
    }

    /**
     * Checks if the lead is assigned to a sales representative.
     *
     * @return true if assigned
     */
    public boolean isAssigned() {
        return assignedTo != null;
    }

    /**
     * Gets the full name of the lead (first name + last name).
     *
     * @return the full name, or email if name is not available
     */
    public String getFullName() {
        if (firstName != null && lastName != null) {
            return firstName + " " + lastName;
        } else if (firstName != null) {
            return firstName;
        } else if (lastName != null) {
            return lastName;
        }
        return email;
    }

    // Getters and setters

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(UUID pipelineId) {
        this.pipelineId = pipelineId;
    }

    public UUID getCurrentStageId() {
        return currentStageId;
    }

    public void setCurrentStageId(UUID currentStageId) {
        this.currentStageId = currentStageId;
    }

    public LeadStatus getStatus() {
        return status;
    }

    public void setStatus(LeadStatus status) {
        this.status = status;
    }

    public LeadSource getSource() {
        return source;
    }

    public void setSource(LeadSource source) {
        this.source = source;
    }

    public Map<String, Object> getSourceDetails() {
        return sourceDetails;
    }

    public void setSourceDetails(Map<String, Object> sourceDetails) {
        this.sourceDetails = sourceDetails;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(UUID assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Map<String, Object> getCustomFields() {
        return customFields;
    }

    public void setCustomFields(Map<String, Object> customFields) {
        this.customFields = customFields;
    }
}

