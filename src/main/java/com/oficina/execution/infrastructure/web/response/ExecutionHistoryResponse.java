package com.oficina.execution.infrastructure.web.response;

import java.time.Instant;
import java.util.UUID;
import com.oficina.execution.domain.model.ExecutionHistory;

public record ExecutionHistoryResponse(UUID id,
        UUID osId,
        UUID diagnosticId,
        UUID repairId,
        String eventType,
        String description,
        Instant createdAt,
        UUID correlationId) {
    public static ExecutionHistoryResponse from(ExecutionHistory value) {
        return new ExecutionHistoryResponse(value.id(),
                value.osId(),
                value.diagnosticId(),
                value.repairId(),
                value.eventType(),
                value.description(),
                value.createdAt(),
                value.correlationId());
    }
}
