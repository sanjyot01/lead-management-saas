package com.leadmanagement.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring for lead scoring (ZSET) and lead-summary caching (STRING + TTL).
 *
 * Explicit String serializers for both keys and values — Spring's default
 * RedisTemplate uses JDK serialization, which writes unreadable bytes and
 * breaks ad-hoc `redis-cli` debugging. Declaring this bean under the name
 * "redisTemplate" also makes Spring Boot's auto-configuration back off from
 * creating its own RedisTemplate/StringRedisTemplate beans, so this is the
 * single StringRedisTemplate in the context.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
