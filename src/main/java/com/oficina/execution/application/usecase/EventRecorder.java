package com.oficina.execution.application.usecase;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.oficina.execution.application.message.*;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.OutboxStatus;
import com.oficina.execution.domain.model.OutboxEvent;

@Service
public class EventRecorder {
    private final OutboxRepositoryPort outbox;
    private final InboxRepositoryPort inbox;
    private final MessageCodecPort codec;

    public EventRecorder(OutboxRepositoryPort outbox, InboxRepositoryPort inbox, MessageCodecPort codec) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.codec = codec;
    }

    public void record(UUID aggregateId, String aggregateType, UUID osId, UUID sagaId, UUID correlationId,
            String eventType, EventPayload payload) {
        UUID correlation = correlationId != null ? correlationId : correlation(osId);
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        String body = codec.serialize(new EventEnvelope<>(eventId, eventType, now, correlation,
                sagaId, osId, "execution-service", payload));
        outbox.save(new OutboxEvent(eventId, aggregateId, aggregateType, eventType, body,
                OutboxStatus.PENDING, sagaId, osId, correlation, now, null, 0, now, null, null, null));
    }

    public UUID correlation(UUID osId) {
        return inbox.findCorrelationByOsId(osId).orElseGet(UUID::randomUUID);
    }
}
