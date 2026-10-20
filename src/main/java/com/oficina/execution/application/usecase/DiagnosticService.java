package com.oficina.execution.application.usecase;

import java.util.UUID;
import com.oficina.execution.application.message.EventPayload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.execution.application.exception.ResourceNotFoundException;
import com.oficina.execution.application.port.in.DiagnosticUseCase;
import com.oficina.execution.application.port.out.DiagnosticRepositoryPort;
import com.oficina.execution.application.port.out.ExecutionHistoryRepositoryPort;
import com.oficina.execution.domain.model.Diagnostic;
import com.oficina.execution.domain.model.ExecutionHistory;
import com.oficina.execution.domain.model.DiagnosticItem;

@Service
public class DiagnosticService implements DiagnosticUseCase {
    private final DiagnosticRepositoryPort diagnostics;
    private final ExecutionHistoryRepositoryPort history;
    private final EventRecorder events;

    public DiagnosticService(DiagnosticRepositoryPort diagnostics, ExecutionHistoryRepositoryPort history, EventRecorder events) {
        this.diagnostics = diagnostics;
        this.history = history;
        this.events = events;
    }

    @Override
    public Diagnostic findById(UUID id) {
        return diagnostics.findById(id).orElseThrow(() -> new ResourceNotFoundException("Diagnostic", id));
    }

    @Override
    public Diagnostic findByOsId(UUID osId) {
        return diagnostics.findByOsId(osId).orElseThrow(() -> new ResourceNotFoundException("Diagnostic", osId));
    }

    @Override
    @Transactional
    public Diagnostic addItem(UUID id, DiagnosticItem item, UUID correlationId) {
        return save(findById(id).addItem(item), "diagnostic.item-added", "Item tecnico adicionado", correlationId);
    }

    @Override
    @Transactional
    public Diagnostic updateObservacoes(UUID id, String observacoes, UUID correlationId) {
        return save(findById(id).updateObservacoes(observacoes),
                "diagnostic.observations-updated", "Observacoes tecnicas atualizadas", correlationId);
    }

    @Override
    @Transactional
    public Diagnostic complete(UUID id, UUID correlationId) {
        Diagnostic value = findById(id).complete();
        UUID correlation = correlationId != null ? correlationId : events.correlation(value.osId());
        Diagnostic completed = save(value, "diagnostic.completed.v1",
                "Diagnostico concluido", correlation);
        events.record(completed.id(), "Diagnostic", completed.osId(), completed.sagaId(), correlation,
                "diagnostic.completed.v1", EventPayload.DiagnosticCompleted.from(completed.items()));
        return completed;
    }

    private Diagnostic save(Diagnostic value, String eventType, String description, UUID correlationId) {
        Diagnostic saved = diagnostics.save(value);
        history.save(ExecutionHistory.create(saved.osId(), saved.id(), null,
                eventType, description, correlationId));
        return saved;
    }

    @Override
    @Transactional
    public Diagnostic fail(UUID id, String reason, UUID correlationId) {
        Diagnostic value = findById(id).fail(reason);
        UUID correlation = correlationId != null ? correlationId : events.correlation(value.osId());
        Diagnostic failed = save(value, "diagnostic.failed.v1",
                "Diagnostico falhou", correlation);
        events.record(failed.id(), "Diagnostic", failed.osId(), failed.sagaId(), correlation,
                "diagnostic.failed.v1", new EventPayload.Failure(failed.observacoes()));
        return failed;
    }
}
