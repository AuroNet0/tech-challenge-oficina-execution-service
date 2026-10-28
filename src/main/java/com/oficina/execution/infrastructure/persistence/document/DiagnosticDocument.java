package com.oficina.execution.infrastructure.persistence.document;

import java.time.Instant;
import java.util.List;
import com.oficina.execution.domain.enums.DiagnosticStatus;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("diagnostics")
@CompoundIndex(name = "queue_status_created", def = "{'status': 1, 'createdAt': 1, '_id': 1}")
public record DiagnosticDocument(
        @Id String id,
        @Indexed(unique = true, name = "unique_os") String osId,
        @Indexed(name = "saga") String sagaId,
        DiagnosticStatus status,
        String observacoes,
        List<DiagnosticItemDocument> items,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        @Version Long version) {
}
