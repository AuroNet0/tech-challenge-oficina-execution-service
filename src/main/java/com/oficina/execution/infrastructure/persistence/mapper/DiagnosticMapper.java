package com.oficina.execution.infrastructure.persistence.mapper;

import java.util.UUID;
import com.oficina.execution.domain.model.Diagnostic;
import com.oficina.execution.infrastructure.persistence.document.DiagnosticDocument;
import com.oficina.execution.infrastructure.persistence.document.DiagnosticItemDocument;

public final class DiagnosticMapper {
    private DiagnosticMapper() {
    }

    public static DiagnosticDocument toDocument(Diagnostic value) {
        return new DiagnosticDocument(
                string(value.id()),
                string(value.osId()),
                string(value.sagaId()),
                value.status(),
                value.observacoes(),
                value.items().stream().map(DiagnosticItemDocument::fromDomain).toList(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt(),
                value.version());
    }

    public static Diagnostic toDomain(DiagnosticDocument value) {
        return new Diagnostic(
                uuid(value.id()),
                uuid(value.osId()),
                uuid(value.sagaId()),
                value.status(),
                value.observacoes(),
                value.items().stream().map(DiagnosticItemDocument::toDomain).toList(),
                value.createdAt(),
                value.updatedAt(),
                value.completedAt(),
                value.version());
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
