package com.oficina.execution.application.port.out;

import java.util.Optional;
import java.util.UUID;
import com.oficina.execution.domain.model.InboxMessage;

public interface InboxRepositoryPort {
    Optional<InboxMessage> findByEventId(UUID eventId);
    Optional<UUID> findCorrelationByOsId(UUID osId);
    void insert(InboxMessage message);
    void update(InboxMessage message);
}
