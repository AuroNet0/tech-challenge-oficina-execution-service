package com.oficina.execution.infrastructure.persistence.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.*;
import com.oficina.execution.application.port.out.OutboxRepositoryPort;
import com.oficina.execution.domain.enums.OutboxStatus;
import com.oficina.execution.domain.model.OutboxEvent;
import com.oficina.execution.infrastructure.persistence.document.OutboxEventDocument;

@Repository
public class OutboxRepositoryAdapter implements OutboxRepositoryPort {
    private final MongoTemplate mongo;

    public OutboxRepositoryAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(OutboxEvent event) {
        var last = mongo.findOne(Query.query(Criteria.where("osId").is(event.osId().toString()))
                .with(Sort.by(Sort.Direction.DESC, "sequence")), OutboxEventDocument.class);
        long sequence = last == null ? 1 : last.sequence() + 1;
        mongo.insert(new OutboxEventDocument(event.eventId().toString(), event.aggregateId().toString(),
                event.aggregateType(), event.eventType(), event.payload(), event.status(), event.sagaId().toString(),
                event.osId().toString(), event.correlationId().toString(), event.createdAt(), event.publishedAt(),
                event.attempts(), event.nextAttemptAt(), event.lastError(), event.lockedUntil(), event.leaseToken(), sequence));
    }

    @Override
    public Optional<OutboxEvent> acquire(Instant now, Duration lease, int maxAttempts) {
        mongo.updateMulti(Query.query(Criteria.where("status").is(OutboxStatus.PROCESSING)
                .and("lockedUntil").lte(now).and("attempts").gte(maxAttempts)),
                new Update().set("status", OutboxStatus.FAILED).set("lastError", "Lease expirou na ultima tentativa")
                        .unset("leaseToken").unset("lockedUntil"), OutboxEventDocument.class);
        Criteria eligible = eligible(now, maxAttempts);
        var pipeline = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").ne(OutboxStatus.PUBLISHED)),
                Aggregation.sort(Sort.Direction.ASC, "sequence"),
                Aggregation.group("osId").first(Aggregation.ROOT).as("head"),
                Aggregation.replaceRoot("head"),
                Aggregation.match(eligible),
                Aggregation.sort(Sort.Direction.ASC, "createdAt"),
                Aggregation.limit(50));
        var heads = mongo.aggregate(pipeline, OutboxEventDocument.class, OutboxEventDocument.class).getMappedResults();
        for (var head : heads) {
            var query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(head.eventId()), eligible));
            var update = new Update().set("status", OutboxStatus.PROCESSING).inc("attempts", 1)
                    .set("lockedUntil", now.plus(lease)).set("leaseToken", UUID.randomUUID().toString());
            var claimed = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), OutboxEventDocument.class);
            if (claimed != null) return Optional.of(toDomain(claimed));
        }
        return Optional.empty();
    }

    private Criteria eligible(Instant now, int maxAttempts) {
        return new Criteria().andOperator(Criteria.where("attempts").lt(maxAttempts),
                new Criteria().orOperator(
                        Criteria.where("status").is(OutboxStatus.PENDING).and("nextAttemptAt").lte(now),
                        Criteria.where("status").is(OutboxStatus.PROCESSING).and("lockedUntil").lte(now)));
    }

    @Override
    public boolean markPublished(UUID eventId, String leaseToken, Instant now) {
        return mongo.updateFirst(leaseQuery(eventId, leaseToken, now), new Update()
                .set("status", OutboxStatus.PUBLISHED).set("publishedAt", now).unset("lastError")
                .unset("lockedUntil").unset("leaseToken"), OutboxEventDocument.class).getModifiedCount() == 1;
    }

    @Override
    public boolean markAttemptFailed(OutboxEvent event, Instant now, int maxAttempts, Duration backoff, String error) {
        var update = new Update().set("status", event.attempts() >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING)
                .set("nextAttemptAt", now.plus(backoff)).set("lastError", error)
                .unset("lockedUntil").unset("leaseToken");
        return mongo.updateFirst(leaseQuery(event.eventId(), event.leaseToken(), now), update,
                OutboxEventDocument.class).getModifiedCount() == 1;
    }

    private Query leaseQuery(UUID eventId, String leaseToken, Instant now) {
        return Query.query(Criteria.where("_id").is(eventId.toString()).and("status").is(OutboxStatus.PROCESSING)
                .and("leaseToken").is(leaseToken).and("lockedUntil").gt(now));
    }

    private OutboxEvent toDomain(OutboxEventDocument value) {
        return new OutboxEvent(UUID.fromString(value.eventId()), UUID.fromString(value.aggregateId()), value.aggregateType(),
                value.eventType(), value.payload(), value.status(), UUID.fromString(value.sagaId()), UUID.fromString(value.osId()),
                UUID.fromString(value.correlationId()), value.createdAt(), value.publishedAt(), value.attempts(),
                value.nextAttemptAt(), value.lastError(), value.lockedUntil(), value.leaseToken());
    }
}
