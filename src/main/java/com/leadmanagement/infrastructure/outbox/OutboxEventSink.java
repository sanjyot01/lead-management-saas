package com.leadmanagement.infrastructure.outbox;

/**
 * A destination for delivered outbox events — the "real publisher" slot that
 * OutboxEventHandler carried as a placeholder log line since Phase 4.
 *
 * Dependency direction: infrastructure DEFINES this interface and knows
 * nothing about implementations; feature modules (e.g. notification)
 * implement it and are discovered by Spring. That keeps the outbox generic
 * while modules opt in to the events they care about.
 *
 * Contract for implementations:
 * - accept() runs INSIDE OutboxEventHandler's per-event transaction, with
 *   TenantContext already set to the event's tenant. Work done here commits
 *   atomically with the event's PROCESSED flag.
 * - Delivery is AT-LEAST-ONCE: a crash after your side effect but before
 *   the shared commit means re-delivery. Implementations MUST be idempotent
 *   (dedupe on event.getId()).
 * - Throwing marks the delivery failed: the event is retried with backoff
 *   and dead-lettered after max retries. Throw for retryable failures;
 *   swallow-and-log only what a retry can never fix.
 */
public interface OutboxEventSink {

    /** Whether this sink wants events of the given type (e.g. "LeadCreatedEvent"). */
    boolean supports(String eventType);

    /** Deliver one event. See the class contract for transaction/idempotency rules. */
    void accept(OutboxEvent event);
}
