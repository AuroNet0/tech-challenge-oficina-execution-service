package com.oficina.execution.application.port.out;

import com.oficina.execution.domain.model.OutboxEvent;

public interface MessageBrokerPort {
    void publish(OutboxEvent event);
}
