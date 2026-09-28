package com.leadmanagement.user.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for tenant registration.
 *
 * Creates:
 * 1. New tenant organization
 * 2. Admin user for that tenant
 *
 * Validation Rules:
 * - tenantSlug: lowercase, alphanumeric, hyphens only (URL-safe)
 * - adminPassword: min 8 chars, 1 uppercase, 1 number (security)
 * - adminEmail: valid email format
 *
 * Example:
 * {
 *   "tenantName": "Wayne Enterprises",
 *   "tenantSlug": "wayne-enterprises",
 *   "adminEmail": "bruce@wayne.com",
 *   "adminPassword": "BatmanRocks123!",
 *   "adminFirstName": "Bruce",
 *   "adminLastName": "Wayne"
 * }
 */
public record RegisterRequest(
    /**
     * Organization name (e.g., "Wayne Enterprises")
     */
    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    String tenantName,

    /**
     * URL-friendly identifier (e.g., "wayne-enterprises")
     * Used in: X-Tenant-ID header, subdomain routing
     * Must be: lowercase, alphanumeric, hyphens only
     */
    @NotBlank(message = "Tenant slug is required")
    @Pattern(
        regexp = "^[a-z0-9-]+$",
        message = "Tenant slug must be lowercase alphanumeric with hyphens only"
    )
    @Size(min = 3, max = 100, message = "Tenant slug must be between 3 and 100 characters")
    String tenantSlug,

    /**
     * Admin user's email
     */
    @NotBlank(message = "Admin email is required")
    @Email(message = "Invalid email format")
    String adminEmail,

    /**
     * Admin user's password (plaintext - will be hashed with BCrypt)
     * Security: min 8 chars, 1 uppercase, 1 number
     */
    @NotBlank(message = "Admin password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
        regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
        message = "Password must contain at least 1 uppercase letter and 1 number"
    )
    String adminPassword,

    /**
     * Admin user's first name
     */
    @NotBlank(message = "Admin first name is required")
    String adminFirstName,

    /**
     * Admin user's last name
     */
    @NotBlank(message = "Admin last name is required")
    String adminLastName
) {
}

