package com.leadmanagement.lead.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 acceptance test — per-tenant fixed-window rate limiting against
 * the Docker Compose Redis (same direct-connection approach as
 * LeadScoringServiceTest; Testcontainers doesn't work on this machine,
 * DEBUGGING.md §5).
 *
 * What is verified:
 * 1. The window budget is enforced exactly: limit N allows N calls, denies
 *    the N+1th.
 * 2. The budget is scoped per TENANT — one tenant exhausting its budget
 *    does not affect another.
 * 3. The counter key carries a TTL (the window actually resets).
 * 4. Redis being unreachable fails OPEN (request allowed, not an exception).
 */
class RateLimitServiceTest {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String REDIS_PASSWORD = "redispass123";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RateLimitService rateLimitService;

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

        rateLimitService = new RateLimitService(redisTemplate);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        // Delete test keys by exact name — never FLUSHALL, compose Redis may hold manual test data
        redisTemplate.delete("lm:" + tenantA + ":rate:lead-ingestion");
        redisTemplate.delete("lm:" + tenantB + ":rate:lead-ingestion");
        TenantContext.clear();
        connectionFactory.destroy();
    }

    @Test
    void budgetEnforcedExactlyAndScopedPerTenant() {
        Duration window = Duration.ofMinutes(5);

        TenantContext.setCurrentTenantId(tenantA);
        assertThat(rateLimitService.tryConsume(3, window)).isTrue();
        assertThat(rateLimitService.tryConsume(3, window)).isTrue();
        assertThat(rateLimitService.tryConsume(3, window)).isTrue();
        assertThat(rateLimitService.tryConsume(3, window))
            .as("4th call within a limit of 3 must be denied")
            .isFalse();

        // Raw Redis proof: counter reached 4 under tenant A's key, and the
        // key expires (the window really does reset).
        String keyA = "lm:" + tenantA + ":rate:lead-ingestion";
        assertThat(redisTemplate.opsForValue().get(keyA)).isEqualTo("4");
        Long ttl = redisTemplate.getExpire(keyA, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(window.toSeconds());

        // Tenant B is untouched by tenant A's exhausted budget.
        TenantContext.setCurrentTenantId(tenantB);
        assertThat(rateLimitService.tryConsume(3, window))
            .as("another tenant's budget must be independent")
            .isTrue();
        assertThat(redisTemplate.opsForValue().get("lm:" + tenantB + ":rate:lead-ingestion"))
            .isEqualTo("1");

        // Retry-After comes from the key's remaining TTL.
        TenantContext.setCurrentTenantId(tenantA);
        long resetSeconds = rateLimitService.windowResetSeconds(window);
        assertThat(resetSeconds).isGreaterThan(0).isLessThanOrEqualTo(window.toSeconds());

        System.out.println("✅ Rate limit proven per-tenant: A denied at 4/3, B allowed, TTL=" + ttl + "s");
    }

    @Test
    void failsOpenWhenRedisUnreachable() {
        // Point at a port nothing listens on; short timeouts keep the test fast.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(500))
            .clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(500))
                    .build())
                .build())
            .build();
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS_HOST, 6390), clientConfig);
        deadFactory.afterPropertiesSet();
        StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();

        RateLimitService failOpenService = new RateLimitService(deadTemplate);

        try {
            TenantContext.setCurrentTenantId(tenantA);
            assertThat(failOpenService.tryConsume(1, Duration.ofMinutes(5)))
                .as("Redis outage must fail open — rate limiting never takes the product down")
                .isTrue();
            assertThat(failOpenService.windowResetSeconds(Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5).toSeconds());
        } finally {
            deadFactory.destroy();
        }
    }
}
