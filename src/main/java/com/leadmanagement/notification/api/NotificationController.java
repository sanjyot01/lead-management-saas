package com.leadmanagement.notification.api;

import com.leadmanagement.notification.application.NotificationService;
import com.leadmanagement.notification.domain.Notification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * In-app notification endpoints. Tenant scoping comes from the JWT via
 * TenantContext.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public List<NotificationResponse> list(@RequestParam(name = "unread", defaultValue = "false") boolean unreadOnly) {
        return notificationService.list(unreadOnly).stream()
            .map(NotificationResponse::fromEntity)
            .toList();
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','REP')")
    public ResponseEntity<NotificationResponse> markRead(@PathVariable("id") UUID id) {
        Notification notification = notificationService.markRead(id);
        return ResponseEntity.ok(NotificationResponse.fromEntity(notification));
    }
}
