package com.leadmanagement.notification.application;

import com.leadmanagement.notification.domain.Notification;
import com.leadmanagement.notification.domain.NotificationType;
import com.leadmanagement.notification.infrastructure.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Notification management for the current tenant (auto-scoped via @TenantId).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a notification from an outbox event, exactly once.
     *
     * Deliberately default (REQUIRED) propagation: when called from
     * LeadNotificationSink inside OutboxEventHandler.handle(), it JOINS the
     * handler's transaction — the notification row and the event's PROCESSED
     * flag commit or roll back together. That single shared commit is what
     * turns the outbox's at-least-once delivery into an exactly-once effect,
     * with the existsBySourceEventId check (plus the partial unique index as
     * the concurrent-delivery backstop) absorbing re-deliveries after a
     * crash-before-commit.
     */
    @Transactional
    public void createFromEvent(NotificationType type, String title, String message,
                                UUID sourceEventId, String correlationId) {
        if (repository.existsBySourceEventId(sourceEventId)) {
            log.info("Notification for outbox event {} already exists — re-delivery absorbed", sourceEventId);
            return;
        }
        repository.save(new Notification(type, title, message, sourceEventId, correlationId));
        log.info("Notification created: type={} sourceEventId={}", type, sourceEventId);
    }

    /** Best-effort notification with no outbox origin (e.g. registration welcome). */
    @Transactional
    public void create(NotificationType type, String title, String message, String correlationId) {
        repository.save(new Notification(type, title, message, null, correlationId));
        log.info("Notification created: type={}", type);
    }

    @Transactional(readOnly = true)
    public List<Notification> list(boolean unreadOnly) {
        return unreadOnly
            ? repository.findByReadAtIsNullOrderByCreatedAtDesc()
            : repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Notification markRead(UUID notificationId) {
        Notification notification = repository.findById(notificationId)
            .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));
        notification.markRead();
        return repository.save(notification);
    }
}
