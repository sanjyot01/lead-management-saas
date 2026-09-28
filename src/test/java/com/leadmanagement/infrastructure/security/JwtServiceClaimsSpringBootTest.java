package com.leadmanagement.infrastructure.security;

import com.leadmanagement.infrastructure.config.JwtConfig;
import com.leadmanagement.user.domain.User;
import com.leadmanagement.user.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = JwtServiceClaimsSpringBootTest.TestConfig.class)
@TestPropertySource(properties = {
    "jwt.secret=lead-management-saas-jwt-secret-key-minimum-256-bits-required-for-hs256-algorithm",
    "jwt.expiration=86400000"
})
class JwtServiceClaimsSpringBootTest {

    private static final String TEST_SECRET = "lead-management-saas-jwt-secret-key-minimum-256-bits-required-for-hs256-algorithm";

    @Autowired
    private JwtService jwtService;

    @Test
    void generatedTokenShouldContainRequiredClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        User user = new User("phase2@test.com", "$2a$10$dummy.hash", "Phase", "Two", UserRole.ADMIN);
        user.setId(userId);
        user.setTenantId(tenantId);

        String token = jwtService.generateToken(user);
        Claims claims = jwtService.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("userId", String.class)).isEqualTo(userId.toString());
        assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("phase2@test.com");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void validateTokenShouldRejectMissingRequiredClaims() {
        SecretKey secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        String userId = UUID.randomUUID().toString();

        String invalidToken = Jwts.builder()
            .subject(userId)
            .claim("userId", userId)
            .claim("tenantId", UUID.randomUUID().toString())
            .claim("email", "phase2@test.com")
            // intentionally omit role claim
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact();

        assertThatThrownBy(() -> jwtService.validateToken(invalidToken))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing role");
    }

    @Configuration
    @EnableConfigurationProperties(JwtConfig.class)
    static class TestConfig {
        @Bean
        JwtService jwtService(JwtConfig jwtConfig) {
            return new JwtService(jwtConfig);
        }
    }
}

