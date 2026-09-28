package com.leadmanagement.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for outbox events.
 * The poller query uses FOR UPDATE SKIP LOCKED to safely process events
 * across multiple application instances without double-processing.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Atomically claim a batch of pending events: select + mark PROCESSING
     * in ONE statement/transaction. A plain "SELECT ... FOR UPDATE SKIP
     * LOCKED" only holds its row lock for the duration of that single
     * query — if marking PROCESSING happened in a later, separate
     * transaction (as an earlier version of this method did), the lock
     * would already be released by then, leaving a window where two
     * instances could both fetch the same still-PENDING row before either
     * claims it. Folding the UPDATE into the same statement that does the
     * SELECT FOR UPDATE SKIP LOCKED closes that window entirely.
     */
    @Query(value = """
        UPDATE outbox_events
        SET status = 'PROCESSING', updated_at = NOW()
        WHERE id IN (
            SELECT id FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= NOW()
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING *
        """, nativeQuery = true)
    List<OutboxEvent> claimPendingEventsForProcessing(@Param("batchSize") int batchSize);
}

