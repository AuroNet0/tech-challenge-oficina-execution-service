package com.oficina.execution.application.message;

import java.time.Instant;
import java.util.UUID;

public record CommandEnvelope(UUID eventId, String eventType, Instant occurredAt, UUID correlationId,
        UUID sagaId, UUID osId, String source, CommandPayload payload) {
}
