package com.leadmanagement.lead.domain;

import com.leadmanagement.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an activity or event on a lead.
 *
 * Activities create an immutable audit trail of all interactions and changes.
 * Used for:
 * - Timeline views in the UI
 * - Engagement scoring
 * - Compliance and audit requirements
 * - Deduplication tracking (FORM_RESUBMITTED activities)
 *
 * Multi-tenant isolated via tenant_id inherited from BaseEntity.
 */
@Entity
@Table(
    name = "lead_activities",
    indexes = {
        @Index(name = "idx_lead_activities_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_lead_activities_lead_id", columnList = "lead_id"),
        @Index(name = "idx_lead_activities_type", columnList = "activity_type"),
        @Index(name = "idx_lead_activities_created_at", columnList = "created_at"),
        @Index(name = "idx_lead_activities_performed_by", columnList = "performed_by")
    }
)
public class LeadActivity extends BaseEntity {

    /**
     * The lead this activity belongs to.
     * Foreign key to leads table.
     */
    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    /**
     * Type of activity performed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 50)
    private LeadActivityType activityType;

    /**
     * Flexible payload containing activity-specific data.
     * Stored as JSONB to accommodate different activity types without schema changes.
     *
     * Examples:
     * - FORM_RESUBMITTED: {"first_name": "John", "company": "Acme Corp", "message": "..."}
     * - STAGE_CHANGED: {"old_stage_id": "uuid", "new_stage_id": "uuid", "old_stage_name": "Contacted", "new_stage_name": "Qualified"}
     * - NOTE_ADDED: {"note": "Called customer, very interested", "is_pinned": false}
     * - EMAIL_SENT: {"email_id": "uuid", "subject": "Follow-up", "preview": "Thank you for..."}
     * - CALL_LOGGED: {"duration_seconds": 300, "outcome": "positive", "notes": "Scheduled demo"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    /**
     * User who performed this activity (nullable for system-generated activities).
     * Foreign key to users table.
     */
    @Column(name = "performed_by")
    private UUID performedBy;

    /**
     * Optional description or summary of the activity.
     * Useful for quick display without parsing payload.
     */
    @Column(name = "description", length = 500)
    private String description;

    // Constructors

    /**
     * JPA requires a no-arg constructor.
     */
    protected LeadActivity() {
        this.payload = new HashMap<>();
    }

    /**
     * Creates a new lead activity.
     *
     * @param leadId the lead this activity belongs to
     * @param activityType the type of activity
     * @param payload the activity data
     */
    public LeadActivity(UUID leadId, LeadActivityType activityType, Map<String, Object> payload) {
        this.leadId = leadId;
        this.activityType = activityType;
        this.payload = payload != null ? payload : new HashMap<>();
    }

    /**
     * Creates a new lead activity with a performer.
     *
     * @param leadId the lead this activity belongs to
     * @param activityType the type of activity
     * @param payload the activity data
     * @param performedBy the user who performed the activity
     */
    public LeadActivity(UUID leadId, LeadActivityType activityType, Map<String, Object> payload, UUID performedBy) {
        this(leadId, activityType, payload);
        this.performedBy = performedBy;
    }

    // Business methods

    /**
     * Adds or updates a payload field.
     *
     * @param key the field name
     * @param value the field value
     */
    public void setPayloadField(String key, Object value) {
        if (this.payload == null) {
            this.payload = new HashMap<>();
        }
        this.payload.put(key, value);
    }

    /**
     * Gets a payload field value.
     *
     * @param key the field name
     * @return the field value, or null if not found
     */
    public Object getPayloadField(String key) {
        return this.payload != null ? this.payload.get(key) : null;
    }

    /**
     * Checks if this activity was performed by a user (vs system-generated).
     *
     * @return true if performed by a user
     */
    public boolean hasPerformer() {
        return performedBy != null;
    }

    /**
     * Checks if this is a form resubmission activity.
     *
     * @return true if activity type is FORM_RESUBMITTED
     */
    public boolean isFormResubmission() {
        return activityType == LeadActivityType.FORM_RESUBMITTED;
    }

    // Getters and setters

    public UUID getLeadId() {
        return leadId;
    }

    public void setLeadId(UUID leadId) {
        this.leadId = leadId;
    }

    public LeadActivityType getActivityType() {
        return activityType;
    }

    public void setActivityType(LeadActivityType activityType) {
        this.activityType = activityType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public UUID getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(UUID performedBy) {
        this.performedBy = performedBy;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

