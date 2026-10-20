package com.oficina.execution.application.port.in;

import java.util.UUID;
import com.oficina.execution.domain.model.Diagnostic;
import com.oficina.execution.domain.model.DiagnosticItem;

public interface DiagnosticUseCase {
    Diagnostic findById(UUID id);
    Diagnostic findByOsId(UUID osId);
    Diagnostic addItem(UUID id, DiagnosticItem item, UUID correlationId);
    Diagnostic updateObservacoes(UUID id, String observacoes, UUID correlationId);
    Diagnostic complete(UUID id, UUID correlationId);
    Diagnostic fail(UUID id, String reason, UUID correlationId);
}
