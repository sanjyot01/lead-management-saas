/**
 * Pipeline module — pipelines and their stages.
 *
 * Responsibilities:
 * - Default pipeline creation on tenant registration (via TenantRegisteredEvent)
 * - Custom pipeline management (tenant admins)
 * - Stage lookup/validation API consumed by the lead module
 *
 * Allowed dependencies:
 * - infrastructure (persistence base, tenant context)
 * - tenant (only for the TenantRegisteredEvent record it listens to)
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"infrastructure", "tenant"}
)
package com.leadmanagement.pipeline;
