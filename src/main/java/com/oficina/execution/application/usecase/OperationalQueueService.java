package com.oficina.execution.application.usecase;

import com.oficina.execution.application.port.in.OperationalQueueUseCase;
import com.oficina.execution.application.port.out.DiagnosticRepositoryPort;
import com.oficina.execution.application.port.out.RepairRepositoryPort;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class OperationalQueueService implements OperationalQueueUseCase {
    private final DiagnosticRepositoryPort diagnostics;
    private final RepairRepositoryPort repairs;

    public OperationalQueueService(DiagnosticRepositoryPort diagnostics, RepairRepositoryPort repairs) {
        this.diagnostics = diagnostics;
        this.repairs = repairs;
    }

    @Override
    public List<WorkItem> pending() {
        return Stream.concat(
                diagnostics.findPending().stream().map(value -> new WorkItem(value.id(), value.osId(),
                        WorkType.DIAGNOSTIC, value.status().name(), value.createdAt())),
                repairs.findPending().stream().map(value -> new WorkItem(value.id(), value.osId(),
                        WorkType.REPAIR, value.status().name(), value.createdAt())))
                .sorted(Comparator.comparing(WorkItem::createdAt).thenComparing(WorkItem::type)
                        .thenComparing(WorkItem::id)).toList();
    }
}
