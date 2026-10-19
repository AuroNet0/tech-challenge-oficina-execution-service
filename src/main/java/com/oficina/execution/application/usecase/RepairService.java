package com.oficina.execution.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.execution.application.exception.ResourceNotFoundException;
import com.oficina.execution.application.port.in.RepairUseCase;
import com.oficina.execution.application.port.out.RepairRepositoryPort;
import com.oficina.execution.application.port.out.ExecutionHistoryRepositoryPort;
import com.oficina.execution.domain.model.Repair;
import com.oficina.execution.domain.model.ExecutionHistory;

@Service
public class RepairService implements RepairUseCase {
    private final RepairRepositoryPort repairs;
    private final ExecutionHistoryRepositoryPort history;

    public RepairService(RepairRepositoryPort repairs, ExecutionHistoryRepositoryPort history) {
        this.repairs = repairs;
        this.history = history;
    }

    @Override
    public Repair findById(UUID id) {
        return repairs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repair", id));
    }

    @Override
    public Repair findByOsId(UUID osId) {
        return repairs.findByOsId(osId).orElseThrow(() -> new ResourceNotFoundException("Repair", osId));
    }

    @Override
    @Transactional
    public Repair updateObservacoes(UUID id, String observacoes, UUID correlationId) {
        return save(findById(id).updateObservacoes(observacoes),
                "repair.observations-updated", "Observacoes tecnicas atualizadas", correlationId);
    }

    @Override
    @Transactional
    public Repair complete(UUID id, UUID correlationId) {
        return save(findById(id).complete(), "repair.completed.locally",
                "Reparo concluido localmente, sem publicacao de evento", correlationId);
    }

    private Repair save(Repair value, String eventType, String description, UUID correlationId) {
        Repair saved = repairs.save(value);
        history.save(ExecutionHistory.create(saved.osId(), saved.diagnosticId(), saved.id(),
                eventType, description, correlationId));
        return saved;
    }
}
