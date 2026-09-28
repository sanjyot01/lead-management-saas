package com.leadmanagement.lead.domain;

/**
 * Represents the current status of a lead in the sales pipeline.
 *
 * Status transitions typically follow this flow:
 * NEW → CONTACTED → QUALIFIED → PROPOSAL_SENT → NEGOTIATION → WON/LOST
 *
 * Leads can be ARCHIVED at any stage for record-keeping without deletion.
 */
public enum LeadStatus {
    /**
     * Lead has been created but not yet contacted.
     */
    NEW,

    /**
     * Initial contact has been made with the lead.
     */
    CONTACTED,

    /**
     * Lead has been qualified as a potential customer (meets ICP criteria).
     */
    QUALIFIED,

    /**
     * Proposal or quote has been sent to the lead.
     */
    PROPOSAL_SENT,

    /**
     * Actively negotiating terms with the lead.
     */
    NEGOTIATION,

    /**
     * Lead has converted to a customer (terminal state).
     */
    WON,

    /**
     * Lead will not convert (terminal state).
     */
    LOST,

    /**
     * Lead has been archived for record-keeping.
     * Different from soft delete - archived leads are queryable.
     */
    ARCHIVED
}

