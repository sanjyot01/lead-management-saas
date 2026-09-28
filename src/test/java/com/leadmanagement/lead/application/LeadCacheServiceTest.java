package com.leadmanagement.lead.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.lead.api.LeadResponse;
import com.leadmanagement.lead.domain.Lead;
import com.leadmanagement.lead.domain.LeadSource;
import com.leadmanagement.lead.infrastructure.LeadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 acceptance test — lead-summary cache-aside contract.
 *
 * Connects to Docker Compose Redis directly (see LeadScoringServiceTest for
 * why not Testcontainers / a Spring context slice). LeadRepository is a
 * Mockito mock so this stays a fast, focused test of the cache behavior
 * (miss → populate → hit → invalidate → miss again) rather than a full
 * Postgres round trip, which TenantIsolationJpaDockerComposeTest already
 * covers for the @TenantId boundary.
 */
class LeadCacheServiceTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String REDIS_PASSWORD = "redispass123";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private LeadRepository leadRepository;
    private LeadCacheService cacheService;

    private UUID tenantId;
    private UUID leadId;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
        config.setPassword(REDIS_PASSWORD);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        leadRepository = mock(LeadRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cacheService = new LeadCacheService(leadRepository, redisTemplate, objectMapper);

        tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenantId(tenantId);

        leadId = UUID.randomUUID();
        Lead lead = new Lead("cache-test@test.com", UUID.randomUUID(), UUID.randomUUID(), LeadSource.MANUAL);
        lead.setId(leadId);
        when(leadRepository.findById(leadId)).thenReturn(Optional.of(lead));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("lm:" + tenantId + ":lead-summary:" + leadId);
        TenantContext.clear();
        connectionFactory.destroy();
    }

    @Test
    void firstReadMissesCacheSecondReadHitsAndTtlIsSet() {
        Optional<LeadResponse> first = cacheService.getLeadSummary(leadId);
        assertThat(first).isPresent();
        assertThat(first.get().email()).isEqualTo("cache-test@test.com");
        verify(leadRepository, times(1)).findById(leadId);

        String key = "lm:" + tenantId + ":lead-summary:" + leadId;
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isGreaterThan(0);

        Optional<LeadResponse> second = cacheService.getLeadSummary(leadId);
        assertThat(second).isPresent();
        assertThat(second.get().email()).isEqualTo("cache-test@test.com");
        // Still only called once — the second read was served from cache, no SQL.
        verify(leadRepository, times(1)).findById(leadId);

        System.out.println("✅ Cache hit avoided a second Postgres read; TTL=" + ttl + "s");
    }

    @Test
    void invalidateForcesNextReadToMissCache() {
        cacheService.getLeadSummary(leadId);
        verify(leadRepository, times(1)).findById(leadId);

        cacheService.invalidate(leadId);

        cacheService.getLeadSummary(leadId);
        verify(leadRepository, times(2)).findById(leadId);
    }
}
