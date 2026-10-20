package com.oficina.execution.infrastructure.messaging;

public class BrokerPublishException extends RuntimeException {
    public BrokerPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
