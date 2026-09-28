package com.leadmanagement.notification.application;

import com.leadmanagement.infrastructure.outbox.OutboxEvent;
import com.leadmanagement.infrastructure.outbox.OutboxEventSink;
import com.leadmanagement.notification.domain.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Turns delivered lead events into in-app notifications — the notification
 * module's implementation of the infrastructure OutboxEventSink port.
 *
 * Runs inside OutboxEventHandler's per-event transaction with the event's
 * tenant already on TenantContext, so the Notification row is auto-scoped
 * and commits atomically with the event's PROCESSED flag. Idempotency
 * against re-delivery comes from NotificationService.createFromEvent()
 * (dedupe on the outbox event id, unique index as backstop).
 */
@Component
public class LeadNotificationSink implements OutboxEventSink {

    private static final Set<String> SUPPORTED = Set.of("LeadCreatedEvent", "LeadStageChangedEvent");

    private final NotificationService notificationService;

    public LeadNotificationSink(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void accept(OutboxEvent event) {
        Map<String, Object> payload = event.getPayload();

        switch (event.getEventType()) {
            case "LeadCreatedEvent" -> notificationService.createFromEvent(
                NotificationType.LEAD_CREATED,
                "New lead: " + payload.getOrDefault("email", "unknown"),
                "A new lead (" + payload.getOrDefault("email", "unknown") + ") was created via "
                    + payload.getOrDefault("source", "UNKNOWN") + ".",
                event.getId(),
                event.getCorrelationId());

            case "LeadStageChangedEvent" -> notificationService.createFromEvent(
                NotificationType.LEAD_STAGE_CHANGED,
                "Lead moved to " + payload.getOrDefault("newStageName", "a new stage"),
                "Lead " + payload.getOrDefault("leadId", "?") + " moved to stage '"
                    + payload.getOrDefault("newStageName", "?") + "' (status "
                    + payload.getOrDefault("newStatus", "?") + ").",
                event.getId(),
                event.getCorrelationId());

            default -> throw new IllegalStateException(
                "Sink invoked for unsupported event type: " + event.getEventType());
        }
    }
}
