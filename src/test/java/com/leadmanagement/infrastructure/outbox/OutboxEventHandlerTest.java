package com.leadmanagement.infrastructure.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the Phase 7 delivery/failure split.
 *
 * The retry and dead-letter paths were only provable at the entity level
 * before this phase (the "publish" step was a log line that could never
 * fail); a throwing sink finally exercises them through the real handler
 * code path.
 */
class OutboxEventHandlerTest {

    private OutboxEventRepository repository;
    private OutboxEventSink supportingSink;
    private OutboxEventSink otherSink;
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        supportingSink = mock(OutboxEventSink.class);
        otherSink = mock(OutboxEventSink.class);
        when(supportingSink.supports("LeadCreatedEvent")).thenReturn(true);
        when(otherSink.supports("LeadCreatedEvent")).thenReturn(false);

        event = new OutboxEvent("Lead", UUID.randomUUID(), "LeadCreatedEvent",
            Map.of("email", "x@y.com"), "corr-1");
    }

    @Test
    void successfulDeliveryInvokesSupportingSinksAndMarksProcessed() {
        OutboxEventHandler handler = new OutboxEventHandler(repository, List.of(supportingSink, otherSink));

        handler.handle(event);

        verify(supportingSink).accept(event);
        verify(otherSink, never()).accept(any());
        assertThat(event.getStatus()).isEqualTo("PROCESSED");
        verify(repository).save(event);
    }

    @Test
    void sinkFailurePropagatesWithoutTouchingTheEvent() {
        org.mockito.Mockito.doThrow(new RuntimeException("downstream down"))
            .when(supportingSink).accept(event);
        OutboxEventHandler handler = new OutboxEventHandler(repository, List.of(supportingSink));

        // handle() must NOT swallow — the caller needs the exception to do
        // failure bookkeeping in a fresh transaction.
        assertThatThrownBy(() -> handler.handle(event))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("downstream down");

        assertThat(event.getStatus()).isEqualTo("PENDING");
        verify(repository, never()).save(any());
    }

    @Test
    void recordFailureBacksOffThenDeadLetters() {
        OutboxEventHandler handler = new OutboxEventHandler(repository, List.of());
        RuntimeException failure = new RuntimeException("still down");

        // Attempts 1-5: retry with growing backoff.
        for (int attempt = 1; attempt <= 5; attempt++) {
            handler.recordFailure(event, failure);
            assertThat(event.getStatus()).isEqualTo("PENDING");
            assertThat(event.getRetryCount()).isEqualTo(attempt);
        }
        assertThat(event.isMaxRetriesReached()).isTrue();

        // Attempt 6: dead-letter.
        handler.recordFailure(event, failure);
        assertThat(event.getStatus()).isEqualTo("FAILED");
        assertThat(event.getErrorMessage()).isEqualTo("still down");
    }
}