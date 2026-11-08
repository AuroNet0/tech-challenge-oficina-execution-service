package com.oficina.execution.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.oficina.execution.domain.model.OutboxEvent;

public interface OutboxRepositoryPort {
    void save(OutboxEvent event);
    Optional<OutboxEvent> acquire(Instant now, Duration lease, int maxAttempts);
    boolean markPublished(UUID eventId, String leaseToken, Instant now);
    boolean markAttemptFailed(OutboxEvent event, Instant now, int maxAttempts, Duration backoff, String error);
}
