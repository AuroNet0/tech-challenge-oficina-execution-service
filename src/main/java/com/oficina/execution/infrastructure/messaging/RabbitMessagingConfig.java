package com.oficina.execution.infrastructure.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({MessagingProperties.class, OutboxProperties.class})
public class RabbitMessagingConfig {
    public static final String COMMANDS = "oficina.commands";
    public static final String EVENTS = "oficina.events";
    public static final String RETRY = "execution.commands.retry";
    public static final String DLX = "execution.commands.dlx";
    public static final String DLQ = "execution.commands.dlq";

    @Bean
    Declarables executionTopology(MessagingProperties properties) {
        var commands = new TopicExchange(COMMANDS, true, false);
        var events = new TopicExchange(EVENTS, true, false);
        var retry = new TopicExchange(RETRY, true, false);
        var dead = new TopicExchange(DLX, true, false);
        var dlq = new Queue(DLQ, true);
        List<Declarable> declarations = new ArrayList<>(List.of(commands, events, retry, dead, dlq,
                BindingBuilder.bind(dlq).to(dead).with("#")));
        for (String command : List.of("diagnostic.start", "repair.start", "repair.cancel")) {
            var queue = new Queue("execution." + command, true);
            var retryQueue = QueueBuilder.durable(queue.getName() + ".retry")
                    .ttl((int) properties.retryDelay().toMillis()).deadLetterExchange(COMMANDS)
                    .deadLetterRoutingKey(command + ".v1").build();
            declarations.addAll(List.of(queue, BindingBuilder.bind(queue).to(commands).with(command + ".v1"),
                    retryQueue, BindingBuilder.bind(retryQueue).to(retry).with(command + ".v1")));
        }
        return new Declarables(declarations);
    }
}
