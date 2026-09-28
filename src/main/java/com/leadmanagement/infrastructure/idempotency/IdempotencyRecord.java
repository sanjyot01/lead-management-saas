package com.leadmanagement.infrastructure.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.TenantId;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One idempotency claim per (tenant, key).
 *
 * Lifecycle: INSERT with null response fields = "in flight" claim, then
 * either completed (response cached for replay until expires_at) or released
 * (row deleted so the client can retry).
 *
 * Standalone @TenantId entity like OutboxEvent, not a BaseEntity subclass:
 * these are infrastructure rows with a TTL, not tenant business data — no
 * soft delete (rows are meant to disappear), no optimistic locking (the
 * unique constraint is the concurrency control).
 */
@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_tenant_key",
        columnNames = {"tenant_id", "idempotency_key"}
    )
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** NULL while the original request is still in flight. */
    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String idempotencyKey, String requestHash, Duration ttl) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plus(ttl);
    }

    public void markCompleted(int responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    /**
     * An expired claim can be reused by a new request with the same key —
     * the previous response is no longer replayable, so the record starts a
     * fresh in-flight lifecycle.
     */
    public void takeOver(String requestHash, Duration ttl) {
        this.requestHash = requestHash;
        this.responseStatus = null;
        this.responseBody = null;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plus(ttl);
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isInFlight() {
        return responseStatus == null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
