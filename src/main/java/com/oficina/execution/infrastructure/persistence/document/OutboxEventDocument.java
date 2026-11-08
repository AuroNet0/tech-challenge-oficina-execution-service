package com.oficina.execution.infrastructure.persistence.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import com.oficina.execution.domain.enums.OutboxStatus;

@Document("outbox_events")
@CompoundIndexes({
    @CompoundIndex(name = "outbox_pending", def = "{'status':1,'nextAttemptAt':1}"),
    @CompoundIndex(name = "outbox_lease", def = "{'status':1,'lockedUntil':1}"),
    @CompoundIndex(name = "outbox_os_sequence", def = "{'osId':1,'sequence':1}", unique = true)
})
public record OutboxEventDocument(@Id String eventId, String aggregateId, String aggregateType, String eventType,
        String payload, OutboxStatus status, String sagaId, String osId, String correlationId, Instant createdAt,
        Instant publishedAt, int attempts, Instant nextAttemptAt, String lastError, Instant lockedUntil,
        String leaseToken, long sequence) {}
