package com.oficina.execution.infrastructure.persistence.mapper;

import java.util.UUID;
import com.oficina.execution.domain.model.ExecutionHistory;
import com.oficina.execution.infrastructure.persistence.document.ExecutionHistoryDocument;

public final class ExecutionHistoryMapper {
    private ExecutionHistoryMapper() {
    }

    public static ExecutionHistoryDocument toDocument(ExecutionHistory value) {
        return new ExecutionHistoryDocument(
                string(value.id()),
                string(value.osId()),
                string(value.diagnosticId()),
                string(value.repairId()),
                value.eventType(),
                value.description(),
                value.createdAt(),
                string(value.correlationId()));
    }

    public static ExecutionHistory toDomain(ExecutionHistoryDocument value) {
        return new ExecutionHistory(
                uuid(value.id()),
                uuid(value.osId()),
                uuid(value.diagnosticId()),
                uuid(value.repairId()),
                value.eventType(),
                value.description(),
                value.createdAt(),
                uuid(value.correlationId()));
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
