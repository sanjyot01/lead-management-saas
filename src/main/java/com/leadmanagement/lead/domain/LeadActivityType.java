package com.leadmanagement.lead.domain;

/**
 * Types of activities that can be recorded on a lead.
 *
 * Activities create an audit trail of all interactions and changes to a lead.
 * Used for timeline views, engagement scoring, and compliance tracking.
 */
public enum LeadActivityType {
    /**
     * Lead with same email resubmitted (deduplication scenario).
     * Payload contains the new submission data.
     */
    FORM_RESUBMITTED,

    /**
     * Lead moved to a different pipeline stage.
     * Payload contains old_stage_id and new_stage_id.
     */
    STAGE_CHANGED,

    /**
     * Manual note added by a user.
     * Payload contains note text.
     */
    NOTE_ADDED,

    /**
     * Email sent to the lead.
     * Payload contains subject, preview, email_id.
     */
    EMAIL_SENT,

    /**
     * Lead opened an email.
     * Payload contains email_id, opened_at.
     */
    EMAIL_OPENED,

    /**
     * Phone call logged with the lead.
     * Payload contains duration, outcome, notes.
     */
    CALL_LOGGED,

    /**
     * Lead assigned to a sales representative.
     * Payload contains assigned_to_user_id, assigned_by_user_id.
     */
    ASSIGNED,

    /**
     * Lead score updated.
     * Payload contains old_score, new_score, reason.
     */
    SCORE_UPDATED,

    /**
     * Lead status changed.
     * Payload contains old_status, new_status.
     */
    STATUS_CHANGED,

    /**
     * Lead created.
     * Payload contains initial lead data.
     */
    CREATED,

    /**
     * Custom activity type defined by tenant.
     * Payload contains activity details.
     */
    CUSTOM
}

