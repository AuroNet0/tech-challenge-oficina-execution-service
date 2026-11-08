package com.oficina.execution.infrastructure.persistence.adapter;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import com.oficina.execution.application.port.out.InboxRepositoryPort;
import com.oficina.execution.domain.enums.InboxStatus;
import com.oficina.execution.domain.model.InboxMessage;
import com.oficina.execution.infrastructure.persistence.document.InboxMessageDocument;

@Repository
public class InboxRepositoryAdapter implements InboxRepositoryPort {
    private final MongoTemplate mongo;

    public InboxRepositoryAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<InboxMessage> findByEventId(UUID eventId) {
        return Optional.ofNullable(mongo.findById(eventId.toString(), InboxMessageDocument.class)).map(this::toDomain);
    }

    @Override
    public Optional<UUID> findCorrelationByOsId(UUID osId) {
        var query = Query.query(Criteria.where("osId").is(osId.toString()).and("status").is(InboxStatus.PROCESSED))
                .with(Sort.by(Sort.Direction.DESC, "receivedAt"));
        return Optional.ofNullable(mongo.findOne(query, InboxMessageDocument.class))
                .map(value -> UUID.fromString(value.correlationId()));
    }

    @Override
    public void insert(InboxMessage message) {
        mongo.insert(toDocument(message));
    }

    @Override
    public void update(InboxMessage message) {
        mongo.save(toDocument(message));
    }

    private InboxMessageDocument toDocument(InboxMessage value) {
        return new InboxMessageDocument(value.eventId().toString(), value.eventType(), value.sagaId().toString(),
                value.osId().toString(), value.correlationId().toString(), value.payload(), value.status(),
                value.receivedAt(), value.processedAt(), value.lastError());
    }

    private InboxMessage toDomain(InboxMessageDocument value) {
        return new InboxMessage(UUID.fromString(value.eventId()), value.eventType(), UUID.fromString(value.sagaId()),
                UUID.fromString(value.osId()), UUID.fromString(value.correlationId()), value.payload(), value.status(),
                value.receivedAt(), value.processedAt(), value.lastError());
    }
}
