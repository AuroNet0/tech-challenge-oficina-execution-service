package com.oficina.execution.infrastructure.persistence.document;

import java.time.Instant;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("execution_history")
@CompoundIndex(name = "history_os_created", def = "{'osId': 1, 'createdAt': 1}")
public record ExecutionHistoryDocument(
        @Id String id,
        String osId,
        String diagnosticId,
        String repairId,
        String eventType,
        String description,
        Instant createdAt,
        String correlationId) {
}
