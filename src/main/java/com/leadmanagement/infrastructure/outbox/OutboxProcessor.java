package com.leadmanagement.infrastructure.outbox;

import com.leadmanagement.infrastructure.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Background worker that polls the outbox table and processes pending events.
 *
 * Claims a batch atomically (SELECT FOR UPDATE SKIP LOCKED folded into the
 * same UPDATE ... RETURNING statement — see OutboxEventRepository) so
 * multiple instances of this app can run simultaneously without
 * double-processing any event. We don't additionally use ShedLock/
 * @SchedulerLock to skip redundant poll *attempts* across instances —
 * that's a minor efficiency gain (fewer wasted queries), not a correctness
 * requirement given the atomic claim above, and not worth a new dependency
 * for this phase.
 *
 * Retry strategy: up to 5 attempts with exponential backoff, then FAILED
 * (dead-letter). Polling interval: 500ms. Batch size: 50.
 *
 * Tenant context on a scheduler thread: this thread never had a request
 * set TenantContext, so Hibernate's SessionFactory (configured for
 * multi-tenancy via hibernate.tenant_identifier_resolver) refuses to open
 * ANY session without one — including for the batch-fetch query below,
 * which spans every tenant. SYSTEM_TENANT_ID is a throwaway sentinel used
 * ONLY to satisfy that gate: outbox_events.tenant_id has no foreign key to
 * tenants, and the fetch query itself has no tenant filter (native SQL,
 * intentionally cross-tenant), so this value is never persisted and never
 * used to scope a real row. Each event's OWN tenant_id (not this sentinel)
 * is what actually matters, and is set per-event below before delegating
 * to OutboxEventHandler.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.processor", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int BATCH_SIZE = 50;
    private static final UUID SYSTEM_TENANT_ID = new UUID(0L, 0L);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventHandler eventHandler;

    public OutboxProcessor(OutboxEventRepository outboxEventRepository, OutboxEventHandler eventHandler) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventHandler = eventHandler;
    }

    /**
     * Poll for pending outbox events every 500ms.
     * Each event is processed in its own transaction (OutboxEventHandler),
     * correctly scoped to that event's own tenant, so failure of one event
     * does not roll back others and no event is processed under the wrong
     * tenant's session.
     */
    @Scheduled(fixedDelay = 500)
    public void processPendingEvents() {
        List<OutboxEvent> events = claimPendingBatch();

        if (events.isEmpty()) {
            return;
        }

        log.debug("Claimed {} outbox events", events.size());

        for (OutboxEvent event : events) {
            TenantContext.setCurrentTenantId(event.getTenantId());
            try {
                eventHandler.handle(event);
            } catch (Exception failure) {
                // handle()'s transaction rolled back whole; the retry/
                // dead-letter bookkeeping needs its own FRESH transaction
                // (see OutboxEventHandler.handle()'s javadoc for why it
                // cannot live inside the failed one).
                try {
                    eventHandler.recordFailure(event, failure);
                } catch (Exception bookkeepingFailure) {
                    // Event stays PROCESSING — visible as stuck until a
                    // reaper exists (known gap, documented). Log loudly.
                    log.error("Could not record failure for outbox event {} — event remains PROCESSING: {}",
                        event.getId(), bookkeepingFailure.getMessage(), bookkeepingFailure);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }

    private List<OutboxEvent> claimPendingBatch() {
        TenantContext.setCurrentTenantId(SYSTEM_TENANT_ID);
        try {
            return outboxEventRepository.claimPendingEventsForProcessing(BATCH_SIZE);
        } finally {
            TenantContext.clear();
        }
    }
}
