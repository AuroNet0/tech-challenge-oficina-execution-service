package com.oficina.execution.infrastructure.persistence.mapper;

import java.util.UUID;
import com.oficina.execution.domain.model.Repair;
import com.oficina.execution.infrastructure.persistence.document.RepairDocument;

public final class RepairMapper {
    private RepairMapper() {
    }

    public static RepairDocument toDocument(Repair value) {
        return new RepairDocument(
                string(value.id()),
                string(value.osId()),
                string(value.sagaId()),
                string(value.diagnosticId()),
                value.status(),
                value.observacoes(),
                value.startedAt(),
                value.completedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.version());
    }

    public static Repair toDomain(RepairDocument value) {
        return new Repair(
                uuid(value.id()),
                uuid(value.osId()),
                uuid(value.sagaId()),
                uuid(value.diagnosticId()),
                value.status(),
                value.observacoes(),
                value.startedAt(),
                value.completedAt(),
                value.createdAt(),
                value.updatedAt(),
                value.version());
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
