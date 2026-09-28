package com.leadmanagement.tenant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant entity representing an organization (customer) in the SaaS platform.
 *
 * Multi-Tenancy Architecture:
 * - Each tenant is a separate organization (e.g., "Wayne Enterprises", "Stark Industries")
 * - Tenants are isolated from each other (cannot see each other's data)
 * - This entity does NOT extend BaseEntity (tenants don't belong to a tenant)
 *
 * Key Concepts:
 * - slug: Human-friendly identifier (e.g., "wayne-enterprises")
 * - subscriptionTier: Pricing plan (STARTER, PROFESSIONAL, ENTERPRISE)
 * - maxLeads: Limit on number of leads per tenant
 *
 * Example:
 * - name: "Wayne Enterprises"
 * - slug: "wayne-enterprises" (used in URLs, X-Tenant-ID header)
 * - status: ACTIVE
 * - subscriptionTier: ENTERPRISE
 */
@Entity
@Table(
    name = "tenants",
    uniqueConstraints = {
        @UniqueConstraint(name = "idx_unique_tenant_slug", columnNames = {"slug"})
    },
    indexes = {
        @Index(name = "idx_tenant_slug", columnList = "slug"),
        @Index(name = "idx_tenant_status", columnList = "status")
    }
)
public class Tenant {

    /**
     * Unique identifier (UUID)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Tenant organization name (e.g., "Wayne Enterprises")
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * URL-friendly identifier (e.g., "wayne-enterprises")
     * Used in X-Tenant-ID header during Phase 1
     * Later used in subdomain routing (wayne-enterprises.yourapp.com)
     */
    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    /**
     * Tenant account status (TRIAL, ACTIVE, SUSPENDED)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status;

    /**
     * Subscription plan (STARTER, PROFESSIONAL, ENTERPRISE)
     */
    @Column(name = "subscription_tier", length = 50)
    private String subscriptionTier;

    /**
     * Maximum number of leads allowed for this tenant
     * Used for quota enforcement
     */
    @Column(name = "max_leads")
    private Integer maxLeads;

    /**
     * Hashed API key for programmatic access
     * Used when integrating with external systems
     */
    @Column(name = "api_key_hash", length = 255)
    private String apiKeyHash;

    /**
     * When the tenant was created
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the tenant was last updated
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Default constructor (required by JPA)
    protected Tenant() {
    }

    /**
     * Create a new tenant
     *
     * @param name Organization name
     * @param slug URL-friendly identifier
     * @param subscriptionTier Pricing plan
     * @param maxLeads Lead quota
     */
    public Tenant(String name, String slug, String subscriptionTier, Integer maxLeads) {
        this.name = name;
        this.slug = slug;
        this.status = TenantStatus.TRIAL; // New tenants start in trial
        this.subscriptionTier = subscriptionTier;
        this.maxLeads = maxLeads;
        this.createdAt = Instant.now();
    }

    /**
     * Called before insert
     */
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    /**
     * Called before update
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public String getSubscriptionTier() {
        return subscriptionTier;
    }

    public void setSubscriptionTier(String subscriptionTier) {
        this.subscriptionTier = subscriptionTier;
    }

    public Integer getMaxLeads() {
        return maxLeads;
    }

    public void setMaxLeads(Integer maxLeads) {
        this.maxLeads = maxLeads;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Activate the tenant (move from TRIAL to ACTIVE)
     */
    public void activate() {
        this.status = TenantStatus.ACTIVE;
    }

    /**
     * Suspend the tenant (payment failure, policy violation)
     */
    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
    }

    /**
     * Check if tenant is active and can create data
     */
    public boolean isActive() {
        return status == TenantStatus.ACTIVE || status == TenantStatus.TRIAL;
    }
}

