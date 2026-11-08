package com.oficina.execution.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OperationalQueueUseCase {
    List<WorkItem> pending();

    enum WorkType { DIAGNOSTIC, REPAIR }

    record WorkItem(UUID id, UUID osId, WorkType type, String status, Instant createdAt) { }
}
