package com.oficina.execution.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("execution.messaging")
public record MessagingProperties(@DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("5s") Duration retryDelay, @DefaultValue("5") int maxDeliveries) {
    public MessagingProperties {
        if (confirmTimeout.isNegative() || confirmTimeout.isZero() || retryDelay.toMillis() < 1
                || retryDelay.toMillis() > Integer.MAX_VALUE || maxDeliveries < 1)
            throw new IllegalArgumentException("Invalid messaging configuration");
    }
}
