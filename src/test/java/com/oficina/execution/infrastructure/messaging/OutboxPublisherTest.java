package com.oficina.execution.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.time.*;
import java.util.*;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.OutboxStatus;
import com.oficina.execution.domain.model.OutboxEvent;
import org.junit.jupiter.api.Test;

class OutboxPublisherTest {
    private OutboxProperties properties(boolean enabled) {
        return new OutboxProperties(enabled, 2, Duration.ofMinutes(1), 3, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "Repair", "repair.started.v1", "{}",
                OutboxStatus.PROCESSING, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), null, 2, Instant.now(), null, Instant.now().plusSeconds(60), "token");
    }

    @Test
    void recordsConfirmOnlyUsingAcquiredLease() {
        var outbox = mock(OutboxRepositoryPort.class);
        var broker = mock(MessageBrokerPort.class);
        var event = event();
        when(outbox.acquire(any(), any(), eq(3))).thenReturn(Optional.of(event));
        when(outbox.markPublished(eq(event.eventId()), eq("token"), any())).thenReturn(true);
        assertThat(new OutboxPublisher(outbox, broker, properties(true)).publishOne()).isTrue();
        verify(broker).publish(event);
        verify(outbox).markPublished(eq(event.eventId()), eq("token"), any());
        verify(outbox, never()).markAttemptFailed(any(), any(), anyInt(), any(), any());
    }

    @Test
    void failedPublicationSchedulesBoundedExponentialBackoff() {
        var outbox = mock(OutboxRepositoryPort.class);
        var broker = mock(MessageBrokerPort.class);
        var event = event();
        when(outbox.acquire(any(), any(), eq(3))).thenReturn(Optional.of(event));
        doThrow(new IllegalStateException("offline")).when(broker).publish(event);
        new OutboxPublisher(outbox, broker, properties(true)).publishOne();
        verify(outbox).markAttemptFailed(eq(event), any(), eq(3), eq(Duration.ofSeconds(4)), eq("IllegalStateException"));
        assertThat(properties(true).backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties(true).backoff(100)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void disabledSchedulerDoesNotAcquireAndEmptyQueueStopsBatch() {
        var outbox = mock(OutboxRepositoryPort.class);
        var broker = mock(MessageBrokerPort.class);
        new OutboxPublisher(outbox, broker, properties(false)).publishPendingEvents();
        verifyNoInteractions(outbox, broker);
        new OutboxPublisher(outbox, broker, properties(true)).publishPendingEvents();
        verify(outbox).acquire(any(), any(), eq(3));
    }
}
