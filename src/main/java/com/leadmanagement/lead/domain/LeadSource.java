package com.leadmanagement.lead.domain;

/**
 * Represents the source channel through which a lead was acquired.
 *
 * Used for attribution tracking and ROI analysis of lead generation channels.
 * Source details are stored separately in Lead.sourceDetails (JSONB) for flexibility.
 */
public enum LeadSource {
    /**
     * Lead manually entered by a user through the UI.
     */
    MANUAL,

    /**
     * Lead ingested via REST API (programmatic integration).
     */
    API,

    /**
     * Lead submitted through a web form (landing page, contact form, etc.).
     */
    FORM,

    /**
     * Lead imported from CSV, spreadsheet, or bulk import.
     */
    IMPORT,

    /**
     * Lead created via third-party integration (Zapier, CRM sync, etc.).
     */
    INTEGRATION
}

