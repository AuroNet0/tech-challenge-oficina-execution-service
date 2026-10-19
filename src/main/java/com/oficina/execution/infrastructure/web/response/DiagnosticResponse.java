package com.oficina.execution.infrastructure.web.response;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.oficina.execution.domain.enums.DiagnosticStatus;
import com.oficina.execution.domain.model.Diagnostic;

public record DiagnosticResponse(UUID id,
        UUID osId,
        UUID sagaId,
        DiagnosticStatus status,
        String observacoes,
        List<DiagnosticItemResponse> items,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Long version) {
    public static DiagnosticResponse from(Diagnostic value) {
        return new DiagnosticResponse(value.id(),
                value.osId(),
                value.sagaId(),
                value.status(),
                value.observacoes(),
                value.items().stream().map(DiagnosticItemResponse::from).toList(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt(),
                value.version());
    }
}
