package com.oficina.execution.application.message;

import java.util.UUID;

public sealed interface CommandPayload {
    record DiagnosticStart(UUID clienteId, UUID veiculoId, UUID filialId, String observacoes) implements CommandPayload {}
    record RepairStart() implements CommandPayload {}
    record RepairCancel(String reason) implements CommandPayload {}
}
