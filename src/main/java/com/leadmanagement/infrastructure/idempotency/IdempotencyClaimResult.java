package com.leadmanagement.infrastructure.idempotency;

/**
 * Outcome of attempting to claim an idempotency key for the current request.
 */
public sealed interface IdempotencyClaimResult {

    /** The key is ours — process the request and cache the response. */
    record NewClaim() implements IdempotencyClaimResult {}

    /** Same key + same request already completed — replay the stored response. */
    record Replay(int responseStatus, String responseBody) implements IdempotencyClaimResult {}

    /** Same key's original request is still being processed — client should retry shortly. */
    record InFlight() implements IdempotencyClaimResult {}

    /** Same key reused with a DIFFERENT request body — a client bug, never replay. */
    record HashMismatch() implements IdempotencyClaimResult {}
}
