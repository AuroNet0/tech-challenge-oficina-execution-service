package com.oficina.execution.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.execution.domain.enums.InboxStatus;

public record InboxMessage(UUID eventId, String eventType, UUID sagaId, UUID osId, UUID correlationId,
        String payload, InboxStatus status, Instant receivedAt, Instant processedAt, String lastError) {
    public InboxMessage finish(InboxStatus result, String error) {
        return new InboxMessage(eventId, eventType, sagaId, osId, correlationId, payload,
                result, receivedAt, Instant.now(), error);
    }
}
