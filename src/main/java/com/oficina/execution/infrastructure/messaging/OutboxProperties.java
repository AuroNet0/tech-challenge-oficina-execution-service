package com.oficina.execution.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("execution.outbox")
public record OutboxProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10") int batchSize,
        @DefaultValue("1m") Duration leaseDuration, @DefaultValue("5") int maxAttempts,
        @DefaultValue("10s") Duration initialBackoff, @DefaultValue("5m") Duration maxBackoff) {
    public OutboxProperties {
        if (batchSize < 1 || batchSize > 1000 || maxAttempts < 1 || leaseDuration.toMillis() < 1
                || initialBackoff.toMillis() < 1 || maxBackoff.compareTo(initialBackoff) < 0)
            throw new IllegalArgumentException("Invalid outbox configuration");
    }

    public Duration backoff(int attempts) {
        long delay = initialBackoff.toMillis();
        long maximum = maxBackoff.toMillis();
        for (int attempt = 1; attempt < attempts && delay < maximum; attempt++)
            delay = delay > maximum / 2 ? maximum : Math.min(delay * 2, maximum);
        return Duration.ofMillis(delay);
    }
}
