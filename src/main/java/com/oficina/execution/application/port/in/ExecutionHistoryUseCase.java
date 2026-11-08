package com.oficina.execution.application.port.in;

import java.util.List;
import java.util.UUID;
import com.oficina.execution.domain.model.ExecutionHistory;

public interface ExecutionHistoryUseCase {
    List<ExecutionHistory> findByOsId(UUID osId);
}
