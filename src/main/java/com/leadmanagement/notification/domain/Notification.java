package com.leadmanagement.notification.domain;

import com.leadmanagement.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An in-app notification for a tenant's team.
 *
 * source_event_id links back to the outbox event that produced it (NULL for
 * notifications with no outbox origin, e.g. the registration welcome). The
 * partial unique index on that column is what makes outbox re-delivery safe:
 * the second insert of the same event loses to the constraint.
 */
@Entity
@SQLDelete(sql = "UPDATE notifications SET deleted_at = NOW(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "notifications",
    indexes = @Index(name = "idx_notifications_tenant_created", columnList = "tenant_id, created_at")
)
public class Notification extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;

    @Column(name = "correlation_id", length = 36, updatable = false)
    private String correlationId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected Notification() {}

    public Notification(NotificationType type, String title, String message,
                        UUID sourceEventId, String correlationId) {
        this.type = type;
        this.title = title;
        this.message = message;
        this.sourceEventId = sourceEventId;
        this.correlationId = correlationId;
    }

    public void markRead() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public Long getVersion() { return version; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public UUID getSourceEventId() { return sourceEventId; }
    public String getCorrelationId() { return correlationId; }
    public LocalDateTime getReadAt() { return readAt; }
}
