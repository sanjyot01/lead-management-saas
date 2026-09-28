package com.leadmanagement.user.domain;

import com.leadmanagement.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * User entity representing a user within a tenant organization.
 *
 * Multi-Tenancy:
 * - Extends BaseEntity which contains @TenantId annotation
 * - Each tenant can have multiple users
 * - Email is unique per tenant (not globally unique)
 *
 * Security:
 * - Password is stored as BCrypt hash (never plaintext)
 * - isActive flag allows soft-disabling users
 *
 * Example:
 * - Tenant: Wayne Enterprises
 * - User: bruce@wayne.com (ADMIN role)
 * - User: alfred@wayne.com (MEMBER role)
 */
@Entity
@SQLDelete(sql = "UPDATE users SET is_active = false, deleted_at = NOW(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL AND is_active = true")
@Table(
    name = "users",
    uniqueConstraints = {
        // Email must be unique per tenant (different tenants can have same email)
        @UniqueConstraint(name = "idx_unique_user_email", columnNames = {"tenant_id", "email"})
    },
    indexes = {
        @Index(name = "idx_user_email", columnList = "email"),
        @Index(name = "idx_user_tenant_id", columnList = "tenant_id")
    }
)
public class User extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * User's email address (unique per tenant)
     */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * BCrypt hashed password (never store plaintext)
     * Hash format: $2a$10$... (60 characters)
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * User's first name
     */
    @Column(name = "first_name", length = 100)
    private String firstName;

    /**
     * User's last name
     */
    @Column(name = "last_name", length = 100)
    private String lastName;

    /**
     * User's role (ADMIN, MEMBER, VIEWER)
     * Determines authorization permissions
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    /**
     * Soft delete flag (false = user disabled)
     * Use this instead of deleting user records
     */
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    // Default constructor (required by JPA)
    protected User() {
    }

    /**
     * Create a new user
     *
     * @param email User's email
     * @param passwordHash BCrypt hashed password
     * @param firstName First name
     * @param lastName Last name
     * @param role User role (ADMIN, MEMBER, VIEWER)
     */
    public User(String email, String passwordHash, String firstName, String lastName, UserRole role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.isActive = true;
    }

    // Getters and Setters

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Get full name (first + last)
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }
}

