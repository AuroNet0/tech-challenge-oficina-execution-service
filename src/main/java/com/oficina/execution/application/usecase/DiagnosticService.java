package com.oficina.execution.application.usecase;

import java.util.UUID;
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

    public DiagnosticService(DiagnosticRepositoryPort diagnostics, ExecutionHistoryRepositoryPort history) {
        this.diagnostics = diagnostics;
        this.history = history;
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
        return save(findById(id).complete(), "diagnostic.completed.locally",
                "Diagnostico concluido localmente, sem publicacao de evento", correlationId);
    }

    private Diagnostic save(Diagnostic value, String eventType, String description, UUID correlationId) {
        Diagnostic saved = diagnostics.save(value);
        history.save(ExecutionHistory.create(saved.osId(), saved.id(), null,
                eventType, description, correlationId));
        return saved;
    }
}
