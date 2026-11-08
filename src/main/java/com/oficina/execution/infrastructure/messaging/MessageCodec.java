package com.oficina.execution.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.oficina.execution.application.exception.InvalidCommandException;
import com.oficina.execution.application.message.*;
import com.oficina.execution.application.port.out.MessageCodecPort;

@Component
public class MessageCodec implements MessageCodecPort {
    private final ObjectMapper mapper;

    public MessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CommandEnvelope readCommand(String body, String routingKey) {
        try {
            JsonNode root = mapper.readTree(body);
            String type = requiredText(root, "eventType");
            if (!type.equals(routingKey) || !"os-service".equals(requiredText(root, "source")))
                throw new InvalidCommandException("Origem ou routing key incompativel");
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) throw new InvalidCommandException("payload deve ser objeto");
            CommandPayload typed = switch (type) {
                case "diagnostic.start.v1" -> {
                    var value = mapper.treeToValue(payload, CommandPayload.DiagnosticStart.class);
                    required(value.clienteId()); required(value.veiculoId()); required(value.filialId());
                    yield value;
                }
                case "repair.start.v1" -> {
                    if (!payload.isEmpty()) throw new InvalidCommandException("repair.start requer payload vazio");
                    yield new CommandPayload.RepairStart();
                }
                case "repair.cancel.v1" -> new CommandPayload.RepairCancel(requiredText(payload, "reason"));
                default -> throw new InvalidCommandException("Comando nao suportado");
            };
            return new CommandEnvelope(uuid(root, "eventId"), type, Instant.parse(requiredText(root, "occurredAt")),
                    uuid(root, "correlationId"), uuid(root, "sagaId"), uuid(root, "osId"), "os-service", typed);
        } catch (InvalidCommandException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidCommandException("Envelope invalido");
        }
    }

    @Override
    public String serialize(EventEnvelope<?> envelope) {
        return mapper.writeValueAsString(envelope);
    }

    private String requiredText(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.hasNonNull(field) || !node.get(field).isString()
                || node.get(field).asString().isBlank()) throw new InvalidCommandException("Campo obrigatorio: " + field);
        return node.get(field).asString();
    }

    private UUID uuid(JsonNode root, String field) {
        String value = requiredText(root, field);
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) throw new InvalidCommandException("UUID invalido");
        return uuid;
    }

    private void required(Object value) {
        if (value == null) throw new InvalidCommandException("Identificadores do diagnostico obrigatorios");
    }
}
