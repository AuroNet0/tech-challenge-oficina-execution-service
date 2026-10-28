package com.oficina.execution.application.usecase;

import java.util.UUID;
import com.oficina.execution.application.message.EventPayload;
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
    private final EventRecorder events;

    public RepairService(RepairRepositoryPort repairs, ExecutionHistoryRepositoryPort history, EventRecorder events) {
        this.repairs = repairs;
        this.history = history;
        this.events = events;
    }

    @Override
    public Repair findById(UUID id) {
        return repairs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repair", id));
    }

    @Override
    @Transactional
    public Repair start(UUID id, UUID correlationId) {
        Repair value = findById(id).authorizeAndStart();
        UUID correlation = correlationId != null ? correlationId : events.correlation(value.osId());
        Repair started = save(value, "repair.started.v1",
                "Reparo iniciado pelo mecanico", correlation);
        events.record(started.id(), "Repair", started.osId(), started.sagaId(), correlation,
                "repair.started.v1", new EventPayload.Empty());
        return started;
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
        Repair value = findById(id).complete();
        UUID correlation = correlationId != null ? correlationId : events.correlation(value.osId());
        Repair completed = save(value, "repair.completed.v1",
                "Reparo concluido", correlation);
        events.record(completed.id(), "Repair", completed.osId(), completed.sagaId(), correlation,
                "repair.completed.v1", new EventPayload.RepairCompleted(completed.id().toString()));
        return completed;
    }

    private Repair save(Repair value, String eventType, String description, UUID correlationId) {
        Repair saved = repairs.save(value);
        history.save(ExecutionHistory.create(saved.osId(), saved.diagnosticId(), saved.id(),
                eventType, description, correlationId));
        return saved;
    }

    @Override
    @Transactional
    public Repair fail(UUID id, String reason, UUID correlationId) {
        Repair value = findById(id).fail(reason);
        UUID correlation = correlationId != null ? correlationId : events.correlation(value.osId());
        Repair failed = save(value, "repair.failed.v1",
                "Reparo falhou", correlation);
        events.record(failed.id(), "Repair", failed.osId(), failed.sagaId(), correlation,
                "repair.failed.v1", new EventPayload.Failure(failed.observacoes()));
        return failed;
    }
}
