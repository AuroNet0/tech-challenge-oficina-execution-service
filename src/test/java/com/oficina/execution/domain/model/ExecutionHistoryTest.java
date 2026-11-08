package com.oficina.execution.domain.model;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import com.oficina.execution.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

class ExecutionHistoryTest {
    @Test
    void createsImmutableNormalizedHistoryWithOptionalReferences() {
        UUID osId = UUID.randomUUID();
        UUID diagnosticId = UUID.randomUUID();
        UUID repairId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var history = ExecutionHistory.create(osId, diagnosticId, repairId, " repair.failed.v1 ", " Falha tecnica ", correlationId);
        assertThat(history.osId()).isEqualTo(osId);
        assertThat(history.diagnosticId()).isEqualTo(diagnosticId);
        assertThat(history.repairId()).isEqualTo(repairId);
        assertThat(history.correlationId()).isEqualTo(correlationId);
        assertThat(history.eventType()).isEqualTo("repair.failed.v1");
        assertThat(history.description()).isEqualTo("Falha tecnica");
        assertThat(history.createdAt()).isNotNull();
        assertThat(ExecutionHistory.class.isRecord()).isTrue();
        assertThat(ExecutionHistory.create(osId, null, null, "technical.note", "Nota", null).diagnosticId()).isNull();
        assertThatThrownBy(() -> ExecutionHistory.create(null, null, null, "event", "Nota", null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExecutionHistory.create(osId, null, null, " ", "Nota", null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExecutionHistory.create(osId, null, null, "event", null, null))
                .isInstanceOf(DomainException.class);
    }
}
