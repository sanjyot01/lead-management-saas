package com.leadmanagement.lead.application;

import com.leadmanagement.infrastructure.config.RedisKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-window counter (INCR + EXPIRE-on-first-hit) rather than a true token
 * bucket: simpler primitive, no Lua script needed. The window boundary burst
 * (up to 2x the limit straddling two windows) is an accepted trade-off at
 * this scale — see the Phase 5 shadow ledger.
 *
 * Scoped per TENANT via RedisKeyFactory.rateLimitKey() — the current tenant
 * comes from TenantContext, so callers must already be inside an
 * authenticated request.
 *
 * Fail open: if Redis is unreachable, the request is allowed through and a
 * warning is logged — same contract as LeadScoringService/LeadCacheService.
 * Rate limiting protects capacity; it must never become the reason the
 * product is down when Redis is.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @return true if this call is within {@code limit} for the current
     *         tenant's {@code window}; false once the window's budget is
     *         exhausted.
     */
    public boolean tryConsume(int limit, Duration window) {
        try {
            String key = RedisKeyFactory.rateLimitKey();
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count != null && count <= limit;
        } catch (Exception ex) {
            log.warn("Redis unavailable — rate limit fail-open: {}", ex.getMessage());
            return true;
        }
    }

    /**
     * Seconds until the current tenant's window resets — used for the
     * Retry-After header on a 429. Falls back to the full window length if
     * the TTL can't be read (fail-open path never reaches here, but Redis
     * could die between the denial and this call).
     */
    public long windowResetSeconds(Duration window) {
        try {
            Long ttl = redisTemplate.getExpire(RedisKeyFactory.rateLimitKey(), TimeUnit.SECONDS);
            return (ttl != null && ttl > 0) ? ttl : window.toSeconds();
        } catch (Exception ex) {
            return window.toSeconds();
        }
    }
}