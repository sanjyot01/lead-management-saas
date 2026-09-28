package com.leadmanagement.infrastructure.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox event record.
 *
 * Written in the SAME transaction as the domain change.
 * A background processor polls this table and publishes events reliably.
 *
 * Status lifecycle: PENDING -> PROCESSING -> PROCESSED | FAILED
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
        @Index(name = "idx_outbox_tenant_id", columnList = "tenant_id")
    }
)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType,
                       Map<String, Object> payload, String correlationId) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.nextAttemptAt = this.createdAt;
    }

    public void markProcessing() {
        this.status = "PROCESSING";
        this.updatedAt = LocalDateTime.now();
    }

    public void markProcessed() {
        this.status = "PROCESSED";
        this.processedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Exponential backoff: 2^retryCount seconds (2s, 4s, 8s, 16s, 32s for
     * attempts 1-5). Without a growing delay, a failing event would get
     * retried on every 500ms poll cycle, hammering an already-failing
     * downstream instead of backing off from it.
     */
    public void incrementRetry() {
        this.retryCount++;
        this.status = "PENDING";
        this.updatedAt = LocalDateTime.now();
        this.nextAttemptAt = this.updatedAt.plusSeconds((long) Math.pow(2, retryCount));
    }

    public boolean isMaxRetriesReached() {
        return this.retryCount >= 5;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getCorrelationId() { return correlationId; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
}

