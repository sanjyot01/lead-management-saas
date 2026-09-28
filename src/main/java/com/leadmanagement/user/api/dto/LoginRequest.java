package com.leadmanagement.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for user login.
 *
 * Authentication Flow:
 * 1. User submits email + password
 * 2. System finds user by email (within tenant)
 * 3. Verifies password (BCrypt comparison)
 * 4. Generates JWT token
 * 5. Returns token to client
 *
 * Example:
 * {
 *   "email": "bruce@wayne.com",
 *   "password": "BatmanRocks123!"
 * }
 */
public record LoginRequest(
    /**
     * User's email address
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    /**
     * User's password (plaintext - will be compared with BCrypt hash)
     */
    @NotBlank(message = "Password is required")
    String password
) {
}

