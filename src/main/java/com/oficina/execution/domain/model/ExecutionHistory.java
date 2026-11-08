package com.oficina.execution.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record ExecutionHistory(UUID id, UUID osId, UUID diagnosticId, UUID repairId,
        String eventType, String description, Instant createdAt, UUID correlationId) {
    public ExecutionHistory {
        DomainValidation.required(id, "id");
        DomainValidation.required(osId, "osId");
        DomainValidation.required(createdAt, "createdAt");
        eventType = DomainValidation.text(eventType, "eventType");
        description = DomainValidation.text(description, "description");
    }

    public static ExecutionHistory create(UUID osId, UUID diagnosticId, UUID repairId,
            String eventType, String description, UUID correlationId) {
        return new ExecutionHistory(UUID.randomUUID(), osId, diagnosticId, repairId,
                eventType, description, Instant.now().truncatedTo(ChronoUnit.MILLIS), correlationId);
    }
}
