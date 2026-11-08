package com.oficina.execution.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.oficina.execution.application.exception.InvalidCommandException;
import com.oficina.execution.application.message.CommandEnvelope;
import com.oficina.execution.application.port.out.MessageCodecPort;
import com.oficina.execution.application.usecase.CommandHandler;
import com.oficina.execution.domain.enums.InboxStatus;

@Component
public class CommandConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandConsumer.class);
    private final MessageCodecPort codec;
    private final CommandHandler handler;
    private final RabbitMessageBrokerAdapter broker;
    private final MessagingProperties properties;

    public CommandConsumer(MessageCodecPort codec, CommandHandler handler,
            RabbitMessageBrokerAdapter broker, MessagingProperties properties) {
        this.codec = codec;
        this.handler = handler;
        this.broker = broker;
        this.properties = properties;
    }

    @RabbitListener(queues = {"execution.diagnostic.start", "execution.repair.start", "execution.repair.cancel"},
            autoStartup = "${execution.messaging.enabled:true}")
    public void consume(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        boolean rejected;
        CommandEnvelope command = null;
        try {
            command = codec.readCommand(body, routingKey);
            rejected = handler.handle(command, body) == InboxStatus.REJECTED;
        } catch (InvalidCommandException exception) {
            rejected = true;
        } catch (RuntimeException exception) {
            log(command, "retry");
            forward(message, routingKey, false);
            return;
        }
        if (rejected) {
            log(command, "rejected");
            forward(message, routingKey, true);
        }
    }

    private void forward(Message original, String routingKey, boolean permanent) {
        Object header = original.getMessageProperties().getHeaders().get("execution-attempt");
        int attempt = header instanceof Number number ? Math.max(0, number.intValue()) : 0;
        boolean dead = permanent || attempt >= properties.maxDeliveries() - 1;
        var metadata = MessagePropertiesBuilder.fromClonedProperties(original.getMessageProperties()).build();
        metadata.setHeader("execution-attempt", attempt + 1);
        metadata.setHeader("execution-result", dead ? "rejected" : "retry");
        metadata.setExpiration(null);
        try {
            broker.send(dead ? RabbitMessagingConfig.DLX : RabbitMessagingConfig.RETRY,
                    routingKey, new Message(original.getBody(), metadata));
            LOGGER.warn("command forwarding messageId={} eventType={} attempt={} result={}",
                    metadata.getMessageId(), routingKey, attempt + 1, dead ? "dlq" : "retry");
        } catch (RuntimeException exception) {
            throw new ImmediateRequeueAmqpException("Transferencia confirmada indisponivel; preservar entrega original", exception);
        }
    }

    private void log(CommandEnvelope command, String result) {
        LOGGER.warn("command eventId={} eventType={} sagaId={} osId={} correlationId={} result={}",
                command == null ? null : command.eventId(), command == null ? null : command.eventType(),
                command == null ? null : command.sagaId(), command == null ? null : command.osId(),
                command == null ? null : command.correlationId(), result);
    }
}
