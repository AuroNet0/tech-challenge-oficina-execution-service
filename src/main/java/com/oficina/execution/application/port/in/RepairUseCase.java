package com.oficina.execution.application.port.in;

import java.util.UUID;
import com.oficina.execution.domain.model.Repair;

public interface RepairUseCase {
    Repair findById(UUID id);
    Repair findByOsId(UUID osId);
    Repair updateObservacoes(UUID id, String observacoes, UUID correlationId);
    Repair complete(UUID id, UUID correlationId);
    Repair fail(UUID id, String reason, UUID correlationId);
}
