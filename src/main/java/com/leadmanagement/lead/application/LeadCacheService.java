package com.leadmanagement.lead.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadmanagement.infrastructure.config.RedisKeyFactory;
import com.leadmanagement.lead.api.LeadResponse;
import com.leadmanagement.lead.domain.Lead;
import com.leadmanagement.lead.infrastructure.LeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside read path for lead summaries.
 *
 * Postgres remains authoritative; Redis only ever holds a short-lived,
 * rebuildable copy. On update, callers must invalidate (DEL) rather than
 * write-through refresh — the next read rebuilds from Postgres, so a stale
 * cache can never diverge from the source of truth for longer than the TTL.
 *
 * Fail open: any Redis error degrades to "always read from Postgres."
 */
@Service
public class LeadCacheService {

    private static final Logger log = LoggerFactory.getLogger(LeadCacheService.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final LeadRepository leadRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public LeadCacheService(LeadRepository leadRepository,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper) {
        this.leadRepository = leadRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the lead summary, serving from cache when present and falling
     * back to Postgres (then re-populating the cache) on a miss.
     */
    public Optional<LeadResponse> getLeadSummary(UUID leadId) {
        String key = RedisKeyFactory.leadSummaryKey(leadId);

        String cached = safeGet(key);
        if (cached != null) {
            Optional<LeadResponse> deserialized = deserialize(cached, leadId);
            if (deserialized.isPresent()) {
                return deserialized;
            }
            // Corrupt/unreadable cache entry — fall through and rebuild from Postgres.
        }

        Optional<Lead> lead = leadRepository.findById(leadId);
        if (lead.isEmpty()) {
            return Optional.empty();
        }

        LeadResponse response = LeadResponse.fromEntity(lead.get());
        safeSet(key, response);
        return Optional.of(response);
    }

    /**
     * Invalidates the cached summary for a lead. Call this on every update —
     * invalidate, don't refresh, so Postgres stays the single writer of truth.
     */
    public void invalidate(UUID leadId) {
        try {
            redisTemplate.delete(RedisKeyFactory.leadSummaryKey(leadId));
        } catch (Exception ex) {
            log.warn("Redis unavailable — could not invalidate cache for lead {}: {}", leadId, ex.getMessage());
        }
    }

    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("Redis unavailable — cache read skipped for key {}: {}", key, ex.getMessage());
            return null;
        }
    }

    private void safeSet(String key, LeadResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception ex) {
            log.warn("Redis unavailable — cache write skipped for key {}: {}", key, ex.getMessage());
        }
    }

    private Optional<LeadResponse> deserialize(String json, UUID leadId) {
        try {
            return Optional.of(objectMapper.readValue(json, LeadResponse.class));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize cached summary for lead {}: {}", leadId, ex.getMessage());
            return Optional.empty();
        }
    }
}