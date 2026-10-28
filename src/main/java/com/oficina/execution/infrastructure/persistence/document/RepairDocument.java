package com.oficina.execution.infrastructure.persistence.document;

import java.time.Instant;
import com.oficina.execution.domain.enums.RepairStatus;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("work_orders")
@CompoundIndex(name = "queue_status_created", def = "{'status': 1, 'createdAt': 1, '_id': 1}")
public record RepairDocument(
        @Id String id,
        @Indexed(unique = true, name = "unique_os") String osId,
        @Indexed(name = "saga") String sagaId,
        String diagnosticId,
        RepairStatus status,
        String observacoes,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        @Version Long version) {
}
