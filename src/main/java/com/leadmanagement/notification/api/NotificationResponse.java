package com.leadmanagement.notification.api;

import com.leadmanagement.notification.domain.Notification;
import com.leadmanagement.notification.domain.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    NotificationType type,
    String title,
    String message,
    boolean read,
    LocalDateTime readAt,
    LocalDateTime createdAt
) {
    public static NotificationResponse fromEntity(Notification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getType(),
            notification.getTitle(),
            notification.getMessage(),
            notification.isRead(),
            notification.getReadAt(),
            notification.getCreatedAt()
        );
    }
}
