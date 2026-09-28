package com.leadmanagement.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT configuration properties.
 *
 * Configuration from application.yml:
 * jwt:
 *   secret: "your-secret-key-min-256-bits"
 *   expiration: 86400000  # 24 hours in milliseconds
 *
 * Security Notes:
 * - Secret must be at least 256 bits for HS256 algorithm
 * - In production, use environment variable for secret
 * - Expiration: 86400000 ms = 24 hours
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtConfig(
    /**
     * Secret key for JWT signing (HS256 algorithm)
     * Must be at least 256 bits (32 characters)
     */
    String secret,

    /**
     * Token expiration time in milliseconds
     * Default: 86400000 (24 hours)
     */
    long expiration
) {
}

