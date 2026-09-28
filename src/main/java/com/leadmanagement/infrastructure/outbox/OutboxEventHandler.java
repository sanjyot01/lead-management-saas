package com.leadmanagement.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Processes a single outbox event in its own transaction.
 *
 * Split out from OutboxProcessor (the @Scheduled poller) for two reasons:
 * 1. A genuinely separate Spring bean means this call goes through the AOP
 *    proxy, so @Transactional actually applies — calling this method via
 *    `this.` from within the same class (as the original scaffold did)
 *    silently bypasses the proxy and the annotation does nothing.
 * 2. REQUIRES_NEW: each event belongs to a different tenant, and Hibernate
 *    resolves the @TenantId session identifier once, at session-open — not
 *    per query (see AuthService.register()'s javadoc / DEBUGGING.md #7 for
 *    the full story of that lesson). A single poll batch spans many
 *    tenants, so each event needs its own freshly-opened session. Unlike
 *    the auth-flow case, this is safe here: outbox_events has no foreign
 *    key on tenant_id, and every event was already durably committed by
 *    the original business transaction before the poller ever saw it — no
 *    same-transaction-visibility trap to fall into.
 *
 * Caller (OutboxProcessor) MUST set TenantContext to event.getTenantId()
 * BEFORE calling handle() — the transactional proxy opens the session at
 * the call boundary, before this method's own body runs.
 */
@Component
public class OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventHandler.class);

    private final OutboxEventRepository outboxEventRepository;
    private final List<OutboxEventSink> sinks;

    public OutboxEventHandler(OutboxEventRepository outboxEventRepository,
                              List<OutboxEventSink> sinks) {
        this.outboxEventRepository = outboxEventRepository;
        this.sinks = sinks;
    }

    /**
     * Delivers one event and marks it PROCESSED — one atomic transaction.
     *
     * No try/catch here, deliberately (changed in Phase 7): sink work JOINS
     * this transaction, so a sink failure marks the shared transaction
     * rollback-only — doing the retry bookkeeping in a catch block INSIDE
     * this same transaction (as the Phase 4 version did) would have its
     * writes silently rolled back at commit, stranding the event in
     * PROCESSING forever. Failure bookkeeping must happen in a FRESH
     * transaction: the caller catches and calls recordFailure().
     * (Latent in Phase 4 — nothing joined the transaction back then, so the
     * catch's save always sat in a clean transaction.)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OutboxEvent event) {
        // Already marked PROCESSING by the atomic claim in
        // OutboxEventRepository.claimPendingEventsForProcessing().

        log.info("Processing outbox event: type={} aggregateId={} correlationId={}",
            event.getEventType(), event.getAggregateId(), event.getCorrelationId());

        // Deliver to every sink that wants this event type (Phase 7 — the
        // Phase 4 placeholder log line finally has real subscribers). Sink
        // side effects and the PROCESSED flag commit together.
        for (OutboxEventSink sink : sinks) {
            if (sink.supports(event.getEventType())) {
                sink.accept(event);
            }
        }

        event.markProcessed();
        outboxEventRepository.save(event);
    }

    /**
     * Records a delivery failure in its own transaction — called by the
     * caller (cross-bean, so the annotation is real) AFTER handle()'s
     * transaction has fully rolled back. Requires TenantContext to still be
     * set to the event's tenant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OutboxEvent event, Exception failure) {
        log.error("Failed to process outbox event {}: {}", event.getId(), failure.getMessage(), failure);

        if (event.isMaxRetriesReached()) {
            log.error("Max retries reached for outbox event {}. Marking as FAILED (dead-letter).", event.getId());
            event.markFailed(failure.getMessage());
        } else {
            event.incrementRetry();
            log.warn("Retrying outbox event {} (attempt {}, next attempt at {})",
                event.getId(), event.getRetryCount(), event.getNextAttemptAt());
        }
        outboxEventRepository.save(event);
    }
}
