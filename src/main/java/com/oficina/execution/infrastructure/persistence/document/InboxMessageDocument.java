package com.oficina.execution.infrastructure.persistence.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import com.oficina.execution.domain.enums.InboxStatus;

@Document("inbox_messages")
@CompoundIndex(name = "inbox_os_received", def = "{'osId':1,'receivedAt':-1}")
public record InboxMessageDocument(@Id String eventId, String eventType, String sagaId, String osId,
        String correlationId, String payload, InboxStatus status, Instant receivedAt, Instant processedAt, String lastError) {}
