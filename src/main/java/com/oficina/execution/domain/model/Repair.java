package com.oficina.execution.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.oficina.execution.domain.enums.CancellationOutcome;
import com.oficina.execution.domain.enums.RepairStatus;
import com.oficina.execution.domain.exception.DomainException;

public record Repair(UUID id, UUID osId, UUID sagaId, UUID diagnosticId, RepairStatus status,
        String observacoes, Instant startedAt, Instant completedAt, Instant createdAt,
        Instant updatedAt, Long version) {
    public Repair {
        DomainValidation.required(id, "id");
        DomainValidation.required(osId, "osId");
        DomainValidation.required(sagaId, "sagaId");
        DomainValidation.required(diagnosticId, "diagnosticId");
        DomainValidation.required(status, "status");
        DomainValidation.required(createdAt, "createdAt");
        DomainValidation.required(updatedAt, "updatedAt");
        observacoes = observacoes == null ? null : observacoes.strip();
        if (updatedAt.isBefore(createdAt)) throw new DomainException("Invalid timestamps");
        if ((status == RepairStatus.PENDING || status == RepairStatus.CANCELLED) && startedAt != null)
            throw new DomainException("Pending or cancelled repair cannot have startedAt");
        if ((status == RepairStatus.IN_PROGRESS || status == RepairStatus.FAILED || status == RepairStatus.COMPLETED)
                && startedAt == null) throw new DomainException("Started repair requires startedAt");
        if (startedAt != null && (startedAt.isBefore(createdAt) || startedAt.isAfter(updatedAt)))
            throw new DomainException("Invalid startedAt");
        if ((status == RepairStatus.COMPLETED) != (completedAt != null))
            throw new DomainException("Invalid completedAt");
        if (completedAt != null && !completedAt.equals(updatedAt)) throw new DomainException("Invalid completion time");
        if (version != null && version < 0) throw new DomainException("Invalid version");
    }

    public static Repair create(UUID osId, UUID sagaId, UUID diagnosticId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Repair(UUID.randomUUID(), osId, sagaId, diagnosticId, RepairStatus.PENDING,
                null, null, null, now, now, null);
    }

    public Repair authorizeAndStart() {
        requireStatus(RepairStatus.PENDING);
        return change(RepairStatus.IN_PROGRESS, observacoes);
    }

    public Repair complete() {
        requireStatus(RepairStatus.IN_PROGRESS);
        return change(RepairStatus.COMPLETED, observacoes);
    }

    public Repair fail(String reason) {
        requireStatus(RepairStatus.IN_PROGRESS);
        return change(RepairStatus.FAILED, DomainValidation.text(reason, "reason"));
    }

    public CancellationOutcome cancellationOutcome() {
        return switch (status) {
            case PENDING -> CancellationOutcome.CANCELLATION_ALLOWED;
            case CANCELLED -> CancellationOutcome.CONFIRMED;
            case IN_PROGRESS, FAILED -> CancellationOutcome.MANUAL_INTERVENTION_REQUIRED;
            case COMPLETED -> CancellationOutcome.COMPENSATION_FAILED;
        };
    }

    public Repair cancel() {
        requireStatus(RepairStatus.PENDING);
        return change(RepairStatus.CANCELLED, observacoes);
    }

    public Repair updateObservacoes(String value) {
        if (status != RepairStatus.PENDING && status != RepairStatus.IN_PROGRESS)
            throw new DomainException("Repair cannot be edited");
        return change(status, value);
    }

    private void requireStatus(RepairStatus expected) {
        if (status != expected) throw new DomainException("Invalid repair transition");
    }

    private Repair change(RepairStatus target, String notes) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (now.isBefore(updatedAt)) now = updatedAt;
        return new Repair(id, osId, sagaId, diagnosticId, target, notes,
                target == RepairStatus.IN_PROGRESS && startedAt == null ? now : startedAt,
                target == RepairStatus.COMPLETED ? now : null, createdAt, now, version);
    }
}
