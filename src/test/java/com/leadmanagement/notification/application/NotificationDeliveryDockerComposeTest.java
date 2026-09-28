package com.leadmanagement.notification.application;

import com.leadmanagement.infrastructure.config.AuditingConfig;
import com.leadmanagement.infrastructure.config.CurrentTenantResolver;
import com.leadmanagement.infrastructure.config.JpaConfig;
import com.leadmanagement.infrastructure.outbox.OutboxEvent;
import com.leadmanagement.infrastructure.outbox.OutboxEventHandler;
import com.leadmanagement.infrastructure.outbox.OutboxEventRepository;
import com.leadmanagement.infrastructure.security.TenantContext;
import com.leadmanagement.notification.domain.Notification;
import com.leadmanagement.notification.domain.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 acceptance test — outbox-driven notification delivery against real
 * Postgres (compose pattern; NOT_SUPPORTED + manual cleanup as usual).
 *
 * What is verified:
 * 1. handle() delivers a LeadCreatedEvent to the notification sink AND marks
 *    the event PROCESSED — notification row and PROCESSED flag both present
 *    (they committed together; a sink failure would have rolled back both).
 * 2. RE-DELIVERY of the same event (crash-before-commit simulation) creates
 *    NO second notification — at-least-once delivery + idempotent consumer
 *    = exactly-once effect, proven with raw SQL.
 * 3. Stage-changed events produce their own notification type.
 * 4. Tenant isolation: tenant B sees none of tenant A's notifications.
 * 5. The inbox round trip: list → markRead → unread filter excludes it.
 */
@DataJpaTest
@Import({JpaConfig.class, CurrentTenantResolver.class, AuditingConfig.class,
    OutboxEventHandler.class, LeadNotificationSink.class, NotificationService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/leadmanagement",
    "spring.datasource.username=leaduser",
    "spring.datasource.password=leadpass123",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationDeliveryDockerComposeTest {

    @Autowired
    private OutboxEventHandler outboxEventHandler;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM notifications WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        TenantContext.clear();
    }

    @Test
    void deliveryCreatesNotificationExactlyOnceEvenWhenRedelivered() {
        TenantContext.setCurrentTenantId(tenantA);
        OutboxEvent event = outboxEventRepository.saveAndFlush(new OutboxEvent(
            "Lead", UUID.randomUUID(), "LeadCreatedEvent",
            Map.of("email", "buyer@example.com", "source", "MANUAL"), "corr-7"));

        outboxEventHandler.handle(event);

        // Notification row + PROCESSED flag both landed (same commit).
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE source_event_id = ? AND tenant_id = ? AND type = 'LEAD_CREATED'",
            Integer.class, event.getId(), tenantA)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ?", String.class, event.getId()))
            .isEqualTo("PROCESSED");

        // Simulate crash-before-commit re-delivery: event back to claimed
        // state, handler runs again.
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PROCESSING' WHERE id = ?", event.getId());
        outboxEventHandler.handle(event);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE source_event_id = ?", Integer.class, event.getId()))
            .as("re-delivery must be absorbed by the consumer-side dedupe")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM outbox_events WHERE id = ?", String.class, event.getId()))
            .isEqualTo("PROCESSED");

        System.out.println("✅ Exactly-once effect proven: 1 notification after 2 deliveries");
    }

    @Test
    void stageChangedEventsGetTheirOwnTypeAndTenantsStayIsolated() {
        TenantContext.setCurrentTenantId(tenantA);
        OutboxEvent stageEvent = outboxEventRepository.saveAndFlush(new OutboxEvent(
            "Lead", UUID.randomUUID(), "LeadStageChangedEvent",
            Map.of("leadId", UUID.randomUUID().toString(), "newStageName", "Qualified", "newStatus", "NEW"),
            "corr-8"));
        outboxEventHandler.handle(stageEvent);

        List<Notification> inboxA = notificationService.list(false);
        assertThat(inboxA).hasSize(1);
        assertThat(inboxA.get(0).getType()).isEqualTo(NotificationType.LEAD_STAGE_CHANGED);
        assertThat(inboxA.get(0).getTitle()).contains("Qualified");

        // Tenant B's inbox is empty — @TenantId scoping, proven at the
        // service level and by raw SQL.
        TenantContext.setCurrentTenantId(tenantB);
        assertThat(notificationService.list(false)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE tenant_id = ?", Integer.class, tenantB)).isZero();

        System.out.println("✅ Notification tenant isolation proven");
    }

    @Test
    void inboxRoundTripListMarkReadUnreadFilter() {
        TenantContext.setCurrentTenantId(tenantA);
        notificationService.create(NotificationType.TENANT_WELCOME, "Welcome!", "Hello.", "corr-9");

        List<Notification> unread = notificationService.list(true);
        assertThat(unread).hasSize(1);

        notificationService.markRead(unread.get(0).getId());

        assertThat(notificationService.list(true)).as("read notifications leave the unread view").isEmpty();
        assertThat(notificationService.list(false)).hasSize(1);
        assertThat(notificationService.list(false).get(0).isRead()).isTrue();
    }
}
