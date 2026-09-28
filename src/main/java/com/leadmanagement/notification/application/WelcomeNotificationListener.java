package com.leadmanagement.notification.application;

import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.notification.domain.NotificationType;
import com.leadmanagement.tenant.domain.events.TenantRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Creates a welcome notification for a newly registered tenant.
 *
 * @Async on purpose — the contrast with DefaultPipelineCreator (synchronous)
 * is the point: the default pipeline is REQUIRED for the tenant to function,
 * so its failure must fail registration; a welcome message is best-effort,
 * so it runs on a pool thread, never delays the registration response, and
 * a failure only logs. (This also can't be @ApplicationModuleListener:
 * that's transactional-event-based, and register() intentionally has no
 * surrounding transaction to hook — see AuthService.)
 *
 * Async means a FRESH thread with no inherited ThreadLocals — the Phase 6
 * contract (event carries tenantId, listener scopes itself) isn't just
 * hygiene here, it's the only way this can work at all.
 */
@Component
public class WelcomeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(WelcomeNotificationListener.class);

    private final NotificationService notificationService;

    public WelcomeNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async
    @EventListener
    public void onTenantRegistered(TenantRegisteredEvent event) {
        TenantContext.setCurrentTenantId(event.tenantId());
        try {
            notificationService.create(
                NotificationType.TENANT_WELCOME,
                "Welcome to LeadFlow!",
                "Your workspace '" + event.tenantSlug() + "' is ready: a default pipeline was "
                    + "created and you can start ingesting leads via POST /api/v1/leads.",
                event.correlationId());
        } catch (Exception e) {
            // Best-effort by design — never let a welcome message break anything.
            log.warn("Could not create welcome notification for tenant {}: {}", event.tenantId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
