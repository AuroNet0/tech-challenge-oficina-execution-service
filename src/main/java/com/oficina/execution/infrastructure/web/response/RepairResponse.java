package com.oficina.execution.infrastructure.web.response;

import java.time.Instant;
import java.util.UUID;
import com.oficina.execution.domain.enums.RepairStatus;
import com.oficina.execution.domain.model.Repair;

public record RepairResponse(UUID id,
        UUID osId,
        UUID sagaId,
        UUID diagnosticId,
        RepairStatus status,
        String observacoes,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
    public static RepairResponse from(Repair value) {
        return new RepairResponse(value.id(),
                value.osId(),
                value.sagaId(),
                value.diagnosticId(),
                value.status(),
                value.observacoes(),
                value.startedAt(),
                value.completedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.version());
    }
}
