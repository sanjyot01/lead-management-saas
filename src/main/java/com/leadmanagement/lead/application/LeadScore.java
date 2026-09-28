package com.leadmanagement.lead.application;

import java.util.UUID;

/**
 * A single entry from the tenant's Redis lead-scores ZSET.
 */
public record LeadScore(UUID leadId, double score) {}
