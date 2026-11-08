package com.oficina.execution.application.port.out;

import java.util.UUID;
import java.util.List;
import com.oficina.execution.domain.model.ExecutionHistory;

public interface ExecutionHistoryRepositoryPort {
    ExecutionHistory save(ExecutionHistory value);
    List<ExecutionHistory> findByOsIdOrderByCreatedAt(UUID osId);
}
