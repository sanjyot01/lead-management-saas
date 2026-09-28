package com.leadmanagement.lead.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.lead.domain.LeadSource;
import com.leadmanagement.lead.domain.events.LeadCreatedEvent;
import com.leadmanagement.lead.domain.events.LeadResubmittedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 acceptance test — Redis ZSET tenant isolation (the Redis analogue
 * of TenantIsolationJpaDockerComposeTest).
 *
 * Connects directly to the Docker Compose Redis instance rather than through
 * a Spring context slice — Testcontainers doesn't work on this machine
 * (DEBUGGING.md §5), and a hand-built LettuceConnectionFactory avoids the
 * flakiness we hit standing up ApplicationContext-backed integration tests
 * against Docker Desktop earlier this session.
 *
 * What is verified:
 * 1. Each tenant's lead-scores ZSET lives under its own key (lm:{tenantId}:lead-scores)
 * 2. LeadCreatedEvent / LeadResubmittedEvent only ever touch the ZSET for the
 *    tenant that was current on TenantContext when the event fired
 * 3. getTopLeads never returns another tenant's leads
 * 4. A missing tenant context fails safe (empty result, not an exception, not
 *    a query against "no tenant")
 */
class LeadScoringServiceTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String REDIS_PASSWORD = "redispass123";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private LeadScoringService scoringService;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
        config.setPassword(REDIS_PASSWORD);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        scoringService = new LeadScoringService(redisTemplate);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        // Delete test keys by exact name — never FLUSHALL, compose Redis may hold manual test data
        redisTemplate.delete("lm:" + tenantA + ":lead-scores");
        redisTemplate.delete("lm:" + tenantB + ":lead-scores");
        TenantContext.clear();
        connectionFactory.destroy();
    }

    @Test
    void tenantScoresNeverCross() {
        UUID leadA1 = UUID.randomUUID();
        UUID leadA2 = UUID.randomUUID();
        UUID leadB1 = UUID.randomUUID();

        TenantContext.setCurrentTenantId(tenantA);
        scoringService.onLeadCreated(new LeadCreatedEvent(leadA1, tenantA, "a1@test.com", LeadSource.MANUAL, "corr-1"));
        scoringService.onLeadCreated(new LeadCreatedEvent(leadA2, tenantA, "a2@test.com", LeadSource.MANUAL, "corr-2"));
        scoringService.onLeadResubmitted(new LeadResubmittedEvent(leadA1, tenantA, "a1@test.com", Map.of(), "corr-3"));

        TenantContext.setCurrentTenantId(tenantB);
        scoringService.onLeadCreated(new LeadCreatedEvent(leadB1, tenantB, "b1@test.com", LeadSource.MANUAL, "corr-4"));

        // Raw Redis proof: each tenant's key holds only its own leads
        Set<String> tenantAMembers = redisTemplate.opsForZSet().range("lm:" + tenantA + ":lead-scores", 0, -1);
        Set<String> tenantBMembers = redisTemplate.opsForZSet().range("lm:" + tenantB + ":lead-scores", 0, -1);

        assertThat(tenantAMembers).containsExactlyInAnyOrder(leadA1.toString(), leadA2.toString());
        assertThat(tenantBMembers).containsExactly(leadB1.toString());

        // leadA1 got created (+10) then resubmitted (+5) = 15; never visible under tenant B
        Double leadA1Score = redisTemplate.opsForZSet().score("lm:" + tenantA + ":lead-scores", leadA1.toString());
        assertThat(leadA1Score).isEqualTo(15.0);

        // getTopLeads is scoped to whichever tenant is current on the thread
        TenantContext.setCurrentTenantId(tenantA);
        List<LeadScore> topA = scoringService.getTopLeads(10);
        assertThat(topA).extracting(LeadScore::leadId).containsExactlyInAnyOrder(leadA1, leadA2);
        assertThat(topA).noneMatch(s -> s.leadId().equals(leadB1));

        TenantContext.setCurrentTenantId(tenantB);
        List<LeadScore> topB = scoringService.getTopLeads(10);
        assertThat(topB).extracting(LeadScore::leadId).containsExactly(leadB1);

        System.out.println("✅ Redis ZSET tenant isolation proven: tenantA=" + topA + " tenantB=" + topB);
    }

    @Test
    void missingTenantContextFailsSafeInsteadOfLeaking() {
        TenantContext.clear();

        // RedisKeyFactory throws IllegalStateException with no tenant context;
        // the service must swallow it (fail-open contract) rather than query
        // some unscoped "no tenant" key.
        List<LeadScore> result = scoringService.getTopLeads(10);

        assertThat(result).isEmpty();
    }
}
