package com.oficina.execution.application.usecase;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.oficina.execution.application.port.in.ExecutionHistoryUseCase;
import com.oficina.execution.application.port.out.ExecutionHistoryRepositoryPort;
import com.oficina.execution.domain.model.ExecutionHistory;

@Service
public class ExecutionHistoryService implements ExecutionHistoryUseCase {
    private final ExecutionHistoryRepositoryPort history;

    public ExecutionHistoryService(ExecutionHistoryRepositoryPort history) {
        this.history = history;
    }

    @Override
    public List<ExecutionHistory> findByOsId(UUID osId) {
        return history.findByOsIdOrderByCreatedAt(osId);
    }
}
