package com.oficina.execution.application.port.out;

import com.oficina.execution.application.message.CommandEnvelope;
import com.oficina.execution.application.message.EventEnvelope;

public interface MessageCodecPort {
    CommandEnvelope readCommand(String body, String routingKey);
    String serialize(EventEnvelope<?> envelope);
}
