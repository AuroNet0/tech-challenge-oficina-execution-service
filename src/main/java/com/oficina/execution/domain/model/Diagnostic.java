package com.oficina.execution.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.oficina.execution.domain.enums.DiagnosticStatus;
import com.oficina.execution.domain.exception.DomainException;

public record Diagnostic(UUID id, UUID osId, UUID sagaId, DiagnosticStatus status,
        String observacoes, List<DiagnosticItem> items, Instant createdAt, Instant updatedAt,
        Instant completedAt, Long version) {
    public Diagnostic {
        DomainValidation.required(id, "id");
        DomainValidation.required(osId, "osId");
        DomainValidation.required(sagaId, "sagaId");
        DomainValidation.required(status, "status");
        DomainValidation.required(createdAt, "createdAt");
        DomainValidation.required(updatedAt, "updatedAt");
        DomainValidation.required(items, "items");
        var identifiers = new HashSet<String>();
        for (var item : items) {
            DomainValidation.required(item, "item");
            if (!identifiers.add(item.itemId())) throw new DomainException("Duplicate itemId");
        }
        items = List.copyOf(items);
        observacoes = observacoes == null ? null : observacoes.strip();
        if (updatedAt.isBefore(createdAt)) throw new DomainException("Invalid timestamps");
        if (status == DiagnosticStatus.COMPLETED && (items.isEmpty() || completedAt == null))
            throw new DomainException("Completed diagnostic requires items and completedAt");
        if (status != DiagnosticStatus.COMPLETED && completedAt != null)
            throw new DomainException("Only completed diagnostics have completedAt");
        if (completedAt != null && !completedAt.equals(updatedAt))
            throw new DomainException("Invalid completedAt");
        if (version != null && version < 0) throw new DomainException("Invalid version");
    }

    public static Diagnostic create(UUID osId, UUID sagaId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Diagnostic(UUID.randomUUID(), osId, sagaId, DiagnosticStatus.PENDING,
                null, List.of(), now, now, null, null);
    }

    public Diagnostic start() {
        requireStatus(DiagnosticStatus.PENDING);
        return change(DiagnosticStatus.IN_PROGRESS, observacoes, items);
    }

    public Diagnostic addItem(DiagnosticItem item) {
        requireStatus(DiagnosticStatus.IN_PROGRESS);
        DomainValidation.required(item, "item");
        var updatedItems = new ArrayList<>(items);
        updatedItems.add(item);
        return change(status, observacoes, updatedItems);
    }

    public Diagnostic updateObservacoes(String value) {
        if (status != DiagnosticStatus.PENDING && status != DiagnosticStatus.IN_PROGRESS)
            throw new DomainException("Diagnostic cannot be edited");
        return change(status, value, items);
    }

    public Diagnostic complete() {
        requireStatus(DiagnosticStatus.IN_PROGRESS);
        return change(DiagnosticStatus.COMPLETED, observacoes, items);
    }

    public Diagnostic fail(String reason) {
        requireStatus(DiagnosticStatus.IN_PROGRESS);
        return change(DiagnosticStatus.FAILED, DomainValidation.text(reason, "reason"), items);
    }

    public Diagnostic cancel() {
        if (status == DiagnosticStatus.COMPLETED || status == DiagnosticStatus.CANCELLED)
            throw new DomainException("Diagnostic cannot be cancelled");
        return change(DiagnosticStatus.CANCELLED, observacoes, items);
    }

    private void requireStatus(DiagnosticStatus expected) {
        if (status != expected) throw new DomainException("Invalid diagnostic transition");
    }

    private Diagnostic change(DiagnosticStatus target, String notes, List<DiagnosticItem> updatedItems) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (now.isBefore(updatedAt)) now = updatedAt;
        return new Diagnostic(id, osId, sagaId, target, notes, updatedItems, createdAt, now,
                target == DiagnosticStatus.COMPLETED ? now : null, version);
    }
}
