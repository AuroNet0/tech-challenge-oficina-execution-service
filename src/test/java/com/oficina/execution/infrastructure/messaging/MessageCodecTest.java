package com.oficina.execution.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.oficina.execution.application.exception.InvalidCommandException;
import com.oficina.execution.application.message.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.DiagnosticItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class MessageCodecTest {
    private final MessageCodec codec = new MessageCodec(JsonMapper.builder().findAndAddModules().build());

    private String body(Object payload, String type) {
        return codec.serialize(new EventEnvelope<>(UUID.randomUUID(), type, Instant.now(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "os-service", payload));
    }

    @Test
    void understandsRealOsServiceCommandPayloads() {
        var diagnostic = new CommandPayload.DiagnosticStart(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "nota");
        assertThat(codec.readCommand(body(diagnostic, "diagnostic.start.v1"), "diagnostic.start.v1").payload()).isEqualTo(diagnostic);
        assertThat(codec.readCommand(body(new CommandPayload.RepairStart(), "repair.start.v1"), "repair.start.v1").payload())
                .isInstanceOf(CommandPayload.RepairStart.class);
        assertThat(codec.readCommand(body(new CommandPayload.RepairCancel("motivo"), "repair.cancel.v1"), "repair.cancel.v1").payload())
                .isEqualTo(new CommandPayload.RepairCancel("motivo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "[]", "{\"eventType\":null}", "{\"eventType\":1}"})
    void rejectsInvalidEnvelopes(String raw) {
        assertThatThrownBy(() -> codec.readCommand(raw, "repair.start.v1")).isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void validatesRequiredFieldsSourceRoutingAndTypedPayload() {
        String raw = body(new CommandPayload.RepairStart(), "repair.start.v1");
        assertThatThrownBy(() -> codec.readCommand(raw, "diagnostic.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(raw.replace("os-service", "unknown"), "repair.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(raw.replace("\"payload\":{}", "\"payload\":null"), "repair.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(body(Map.of(), "diagnostic.start.v1"), "diagnostic.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(body(Map.of(), "repair.cancel.v1"), "repair.cancel.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(body(Map.of("status", "IN_PROGRESS"), "repair.start.v1"), "repair.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> codec.readCommand(body(Map.of(), "other.v1"), "other.v1")).isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void serializesEveryDiagnosticItemAsDecimalStrings() {
        var item = new DiagnosticItem("a", "Peca", DiagnosticItemType.PECA, new BigDecimal("1.500"),
                PriceUnit.UNIDADE, new BigDecimal("1234567890123456789012345678901234567890.00100"));
        String raw = body(EventPayload.DiagnosticCompleted.from(List.of(item)), "diagnostic.completed.v1");
        assertThat(raw).contains("\"quantidade\":\"1.500\"", "\"precoPrevisto\":\"1234567890123456789012345678901234567890.00100\"",
                "\"itemId\":\"a\"", "\"unidadePreco\":\"UNIDADE\"");
    }
}
