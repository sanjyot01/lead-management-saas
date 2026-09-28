/**
 * Lead module - Core business logic for lead management.
 *
 * This module handles:
 * - Lead creation and lifecycle management
 * - Lead deduplication (email-based with activity tracking)
 * - Lead scoring (Redis ZSET integration)
 * - Lead activity timeline
 * - Pipeline stage transitions
 *
 * Dependencies:
 * - infrastructure (for BaseEntity, security, persistence)
 * - pipeline (for pipeline and stage references)
 *
 * Events published:
 * - LeadCreatedEvent
 * - LeadResubmittedEvent
 * - LeadStatusChangedEvent
 * - LeadAssignedEvent
 * - LeadScoredEvent
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"infrastructure", "pipeline"}
)
package com.leadmanagement.lead;

