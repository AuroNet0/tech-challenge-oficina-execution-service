package com.oficina.execution.application.port.out;

import java.util.UUID;

public interface TechnicalAuthorizationPort {
    Decision decide(Request request);

    enum Capability {
        DIAGNOSTIC_READ, DIAGNOSTIC_WRITE, DIAGNOSTIC_COMPLETE,
        REPAIR_READ, REPAIR_WRITE, REPAIR_COMPLETE, HISTORY_READ
    }

    record Request(UUID usuarioId, UUID osId, Capability capability, UUID correlationId) { }
    record Decision(UUID usuarioId, UUID osId, Capability capability, UUID correlationId, boolean allowed) { }
}
