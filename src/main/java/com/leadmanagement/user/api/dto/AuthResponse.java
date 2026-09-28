package com.leadmanagement.user.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for authentication (login/register).
 *
 * Contains:
 * - JWT token (for Authorization header in subsequent requests)
 * - User information (for display)
 * - Expiration time (for client-side token refresh)
 *
 * Client Usage:
 * 1. Store token in localStorage or memory
 * 2. Send in Authorization header: Bearer {token}
 * 3. Refresh token before expiration (or re-login)
 *
 * Example:
 * {
 *   "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *   "userId": "123e4567-e89b-12d3-a456-426614174000",
 *   "tenantId": "987fcdeb-51a2-43f7-9876-543210fedcba",
 *   "email": "bruce@wayne.com",
 *   "role": "ADMIN",
 *   "expiresAt": "2026-03-12T00:00:00Z"
 * }
 */
public record AuthResponse(
    /**
     * JWT token string (use in Authorization: Bearer {token})
     */
    String token,

    /**
     * User's unique identifier
     */
    UUID userId,

    /**
     * Tenant's unique identifier
     */
    UUID tenantId,

    /**
     * User's email
     */
    String email,

    /**
     * User's role (ADMIN, MEMBER, VIEWER)
     */
    String role,

    /**
     * When the token expires (client should refresh before this)
     */
    Instant expiresAt
) {
}

