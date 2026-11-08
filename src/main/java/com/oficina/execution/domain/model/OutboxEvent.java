package com.oficina.execution.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.execution.domain.enums.OutboxStatus;

public record OutboxEvent(UUID eventId, UUID aggregateId, String aggregateType, String eventType,
        String payload, OutboxStatus status, UUID sagaId, UUID osId, UUID correlationId, Instant createdAt,
        Instant publishedAt, int attempts, Instant nextAttemptAt, String lastError, Instant lockedUntil,
        String leaseToken) {
}
