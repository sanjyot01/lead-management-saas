package com.leadmanagement.infrastructure.outbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 acceptance test — exponential backoff and dead-letter thresholds.
 *
 * Plain unit test, no Spring/DB needed: this is pure logic on the entity.
 */
class OutboxEventTest {

    private OutboxEvent newEvent() {
        return new OutboxEvent("Lead", UUID.randomUUID(), "LeadCreatedEvent", Map.of(), "corr-1");
    }

    @Test
    void nextAttemptAtDefaultsToCreationTime() {
        OutboxEvent event = newEvent();
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
    }

    @Test
    void incrementRetryPushesNextAttemptExponentially() {
        OutboxEvent event = newEvent();

        LocalDateTime beforeFirstRetry = LocalDateTime.now();
        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        // 2^1 = 2 seconds
        assertThat(event.getNextAttemptAt()).isAfter(beforeFirstRetry.plusSeconds(1));
        assertThat(event.getNextAttemptAt()).isBefore(beforeFirstRetry.plusSeconds(3));

        LocalDateTime beforeSecondRetry = LocalDateTime.now();
        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(2);
        // 2^2 = 4 seconds — strictly longer than the first backoff
        assertThat(event.getNextAttemptAt()).isAfter(beforeSecondRetry.plusSeconds(3));
        assertThat(event.getNextAttemptAt()).isBefore(beforeSecondRetry.plusSeconds(5));
    }

    @Test
    void maxRetriesReachedAfterFiveAttempts() {
        OutboxEvent event = newEvent();

        for (int i = 0; i < 4; i++) {
            event.incrementRetry();
            assertThat(event.isMaxRetriesReached())
                .as("attempt %d should not yet be at the max-retries threshold", i + 1)
                .isFalse();
        }

        event.incrementRetry();
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.isMaxRetriesReached()).isTrue();
    }

    @Test
    void markFailedSetsTerminalStatus() {
        OutboxEvent event = newEvent();
        for (int i = 0; i < 5; i++) {
            event.incrementRetry();
        }

        event.markFailed("downstream unreachable");

        assertThat(event.getStatus()).isEqualTo("FAILED");
        assertThat(event.getErrorMessage()).isEqualTo("downstream unreachable");
    }
}
