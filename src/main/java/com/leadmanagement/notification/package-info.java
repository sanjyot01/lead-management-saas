/**
 * Notification module — in-app notifications for tenant teams.
 *
 * Responsibilities:
 * - Consuming delivered outbox events (LeadNotificationSink implements the
 *   infrastructure OutboxEventSink port — at-least-once delivery, idempotent
 *   consumption)
 * - Best-effort welcome notification on tenant registration (@Async listener)
 * - Notification inbox API (list, mark read)
 *
 * Allowed dependencies:
 * - infrastructure (persistence base, tenant context, the OutboxEventSink port)
 * - tenant (only for the TenantRegisteredEvent record it listens to)
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"infrastructure", "tenant"}
)
package com.leadmanagement.notification;
