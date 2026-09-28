package com.leadmanagement.infrastructure.security;

import com.leadmanagement.infrastructure.config.JwtConfig;
import com.leadmanagement.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT Service for token generation and validation.
 *
 * Token Structure:
 * {
 *   "sub": "user-uuid",           // Subject (user ID)
 *   "tenantId": "tenant-uuid",    // Tenant ID for multi-tenancy
 *   "email": "user@example.com",  // User's email
 *   "role": "ADMIN",              // User's role
 *   "iat": 1710123456,            // Issued at timestamp
 *   "exp": 1710209856             // Expiration timestamp
 * }
 *
 * Algorithm: HS256 (HMAC with SHA-256)
 *
 * Usage:
 * 1. On login: generateToken(user) → Returns JWT string
 * 2. On request: validateToken(token) → Returns claims if valid
 * 3. Extract data: extractUserId(token), extractTenantId(token)
 */
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final JwtConfig jwtConfig;
    private final SecretKey secretKey;

    public JwtService(JwtConfig jwtConfig) {
        this.jwtConfig = jwtConfig;
        // Convert secret string to SecretKey for HS256 algorithm
        this.secretKey = Keys.hmacShaKeyFor(jwtConfig.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate JWT token for a user.
     *
     * Process:
     * 1. Set subject (user ID)
     * 2. Add custom claims (tenantId, email, role)
     * 3. Set issued time and expiration
     * 4. Sign with HS256 algorithm
     *
     * @param user User to generate token for
     * @return JWT token string (e.g., "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
     */
    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(jwtConfig.expiration());

        return Jwts.builder()
                // Subject: User ID
                .subject(user.getId().toString())
                // Custom claims
                .claim(CLAIM_USER_ID, user.getId().toString())
                .claim(CLAIM_TENANT_ID, user.getTenantId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                // Timestamps
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                // Sign with HS256
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validate JWT token and extract claims.
     *
     * Validation checks:
     * 1. Signature is valid (signed with our secret key)
     * 2. Token is not expired
     * 3. Token format is correct
     *
     * @param token JWT token string
     * @return Claims if valid
     * @throws io.jsonwebtoken.JwtException if token is invalid or expired
     */
    public Claims validateToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        validateRequiredClaims(claims);
        return claims;
    }

    private void validateRequiredClaims(Claims claims) {
        String subject = claims.getSubject();
        String userId = claims.get(CLAIM_USER_ID, String.class);
        String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
        String email = claims.get(CLAIM_EMAIL, String.class);
        String role = claims.get(CLAIM_ROLE, String.class);

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT missing subject claim");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("JWT missing userId claim");
        }
        if (!subject.equals(userId)) {
            throw new IllegalArgumentException("JWT subject and userId claims do not match");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("JWT missing tenantId claim");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("JWT missing email claim");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("JWT missing role claim");
        }
    }

    /**
     * Extract user ID from token.
     *
     * @param token JWT token
     * @return User UUID
     */
    public UUID extractUserId(String token) {
        Claims claims = validateToken(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extract tenant ID from token.
     *
     * @param token JWT token
     * @return Tenant UUID
     */
    public UUID extractTenantId(String token) {
        Claims claims = validateToken(token);
        return UUID.fromString(claims.get(CLAIM_TENANT_ID, String.class));
    }

    /**
     * Extract email from token.
     *
     * @param token JWT token
     * @return User's email
     */
    public String extractEmail(String token) {
        Claims claims = validateToken(token);
        return claims.get(CLAIM_EMAIL, String.class);
    }

    /**
     * Extract role from token.
     *
     * @param token JWT token
     * @return User's role (ADMIN, MEMBER, VIEWER)
     */
    public String extractRole(String token) {
        Claims claims = validateToken(token);
        return claims.get(CLAIM_ROLE, String.class);
    }

    /**
     * Check if token is expired.
     *
     * @param token JWT token
     * @return true if expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = validateToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }
}

