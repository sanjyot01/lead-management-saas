package com.leadmanagement.infrastructure.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Transactional core of the reserve-first idempotency pattern.
 *
 * Why claim() and resolveExisting() are separate methods instead of one
 * try/catch: claim()'s INSERT hitting the unique constraint poisons its
 * transaction (Postgres aborts it; any further statement would fail), so the
 * duplicate-key path MUST run in a fresh transaction. The non-transactional
 * caller (IdempotencyFilter) catches the violation between the two calls —
 * each @Transactional method here is its own complete transaction because
 * the call arrives through the Spring proxy from outside the bean. Putting
 * both steps inside one method of this class would be the Phase 3/4
 * self-invocation trap all over again.
 *
 * All methods require an authenticated request: TenantContext must be set,
 * since IdempotencyRecord is @TenantId-scoped.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repository;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRecordRepository repository,
                              @Value("${leadmanagement.idempotency.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /**
     * Reserves the key for this request by inserting the claim row NOW,
     * before any processing happens.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the
     *         key is already claimed for this tenant — caller must then ask
     *         {@link #resolveExisting} what to do.
     */
    @Transactional
    public void claim(String idempotencyKey, String requestHash) {
        repository.saveAndFlush(new IdempotencyRecord(idempotencyKey, requestHash, ttl));
    }

    /**
     * Classifies an already-claimed key. Runs in its own fresh transaction
     * (see class comment for why it cannot share claim()'s).
     */
    @Transactional
    public IdempotencyClaimResult resolveExisting(String idempotencyKey, String requestHash) {
        IdempotencyRecord record = repository.findByIdempotencyKey(idempotencyKey).orElse(null);

        if (record == null) {
            // Claimed a moment ago but gone now (released by a failed request,
            // or purged). Reclaim it for this request.
            repository.saveAndFlush(new IdempotencyRecord(idempotencyKey, requestHash, ttl));
            return new IdempotencyClaimResult.NewClaim();
        }

        if (record.isExpired()) {
            // The cached response is past its 24h replay window — same key is
            // treated as a brand-new request.
            record.takeOver(requestHash, ttl);
            repository.save(record);
            return new IdempotencyClaimResult.NewClaim();
        }

        if (!record.getRequestHash().equals(requestHash)) {
            return new IdempotencyClaimResult.HashMismatch();
        }

        if (record.isInFlight()) {
            return new IdempotencyClaimResult.InFlight();
        }

        return new IdempotencyClaimResult.Replay(record.getResponseStatus(), record.getResponseBody());
    }

    /** Caches the response so future retries of this key replay it. */
    @Transactional
    public void complete(String idempotencyKey, int responseStatus, String responseBody) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresentOrElse(
            record -> {
                record.markCompleted(responseStatus, responseBody);
                repository.save(record);
            },
            () -> log.warn("Idempotency claim vanished before completion for key {}", idempotencyKey));
    }

    /**
     * Drops the claim after a failure whose response must NOT be replayed
     * (5xx, auth-dependent 4xx, or an exception mid-request) so the client's
     * retry gets a real second attempt. Hard delete is deliberate: this is
     * TTL'd infrastructure state, not tenant business data (Rule 9 protects
     * audit trails; an unfulfilled claim has nothing to audit).
     */
    @Transactional
    public void release(String idempotencyKey) {
        repository.deleteByIdempotencyKey(idempotencyKey);
    }
}
