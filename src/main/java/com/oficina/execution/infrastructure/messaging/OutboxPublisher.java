package com.oficina.execution.infrastructure.messaging;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.model.OutboxEvent;

@Component
public class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepositoryPort outbox;
    private final MessageBrokerPort broker;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxRepositoryPort outbox, MessageBrokerPort broker, OutboxProperties properties) {
        this.outbox = outbox;
        this.broker = broker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${execution.outbox.fixed-delay:5s}")
    public void publishPendingEvents() {
        if (!properties.enabled()) return;
        for (int attempt = 0; attempt < properties.batchSize(); attempt++) {
            if (!publishOne()) break;
        }
    }

    public boolean publishOne() {
        var next = outbox.acquire(Instant.now(), properties.leaseDuration(), properties.maxAttempts());
        if (next.isEmpty()) return false;
        OutboxEvent event = next.get();
        log(event, "attempt");
        try {
            broker.publish(event);
            boolean updated = outbox.markPublished(event.eventId(), event.leaseToken(), Instant.now());
            log(event, updated ? "published" : "lease-lost");
        } catch (RuntimeException exception) {
            boolean updated = outbox.markAttemptFailed(event, Instant.now(), properties.maxAttempts(),
                    properties.backoff(event.attempts()), exception.getClass().getSimpleName());
            log(event, !updated ? "lease-lost" : event.attempts() >= properties.maxAttempts() ? "failed" : "retry");
        }
        return true;
    }

    private void log(OutboxEvent event, String result) {
        LOGGER.info("outbox eventId={} eventType={} sagaId={} osId={} correlationId={} attempt={} result={}",
                event.eventId(), event.eventType(), event.sagaId(), event.osId(), event.correlationId(), event.attempts(), result);
    }
}
