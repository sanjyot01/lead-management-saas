package com.leadmanagement.lead.application;

import com.leadmanagement.infrastructure.config.RedisKeyFactory;
import com.leadmanagement.lead.domain.events.LeadCreatedEvent;
import com.leadmanagement.lead.domain.events.LeadResubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains a per-tenant Redis ZSET of lead scores for sub-millisecond ranking.
 *
 * Postgres's {@code leads.score} column remains the source of truth; this
 * ZSET is derived data that must be rebuildable from Postgres at any time.
 *
 * Listens synchronously (plain @EventListener, same as OutboxPublisher) —
 * NOT @Async. TenantContext is a ThreadLocal set by the request filter, so
 * scoring must run on the request thread to see the right tenant; an async
 * listener would run on a worker thread with no tenant context at all.
 *
 * Fail open: a Redis outage must never fail lead creation. Every call here
 * is wrapped and logged, never rethrown.
 */
@Service
public class LeadScoringService {

    private static final Logger log = LoggerFactory.getLogger(LeadScoringService.class);

    private static final double CREATED_SCORE = 10;
    private static final double RESUBMITTED_SCORE_BUMP = 5;

    private final StringRedisTemplate redisTemplate;

    public LeadScoringService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @EventListener
    public void onLeadCreated(LeadCreatedEvent event) {
        incrementScore(event.leadId(), CREATED_SCORE, "LeadCreatedEvent");
    }

    @EventListener
    public void onLeadResubmitted(LeadResubmittedEvent event) {
        incrementScore(event.leadId(), RESUBMITTED_SCORE_BUMP, "LeadResubmittedEvent");
    }

    private void incrementScore(UUID leadId, double delta, String eventName) {
        try {
            String key = RedisKeyFactory.leadScoresKey();
            Double newScore = redisTemplate.opsForZSet().incrementScore(key, leadId.toString(), delta);
            log.debug("{} bumped Redis score for lead {} by {} -> {}", eventName, leadId, delta, newScore);
        } catch (Exception ex) {
            log.warn("Redis unavailable — skipped score update for lead {} ({}): {}",
                leadId, eventName, ex.getMessage());
        }
    }

    /**
     * Top N leads for the current tenant, highest score first.
     * Fail open: returns an empty list (never throws) if Redis is unavailable
     * or no tenant context is set.
     */
    public List<LeadScore> getTopLeads(int n) {
        try {
            String key = RedisKeyFactory.leadScoresKey();
            Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, n - 1);

            if (tuples == null) {
                return List.of();
            }
            return tuples.stream()
                .map(t -> new LeadScore(UUID.fromString(t.getValue()), t.getScore()))
                .toList();
        } catch (Exception ex) {
            log.warn("Redis unavailable — returning empty top-leads list: {}", ex.getMessage());
            return List.of();
        }
    }
}