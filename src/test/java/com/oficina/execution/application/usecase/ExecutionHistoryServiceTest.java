package com.oficina.execution.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.execution.application.port.out.ExecutionHistoryRepositoryPort;
import com.oficina.execution.domain.model.ExecutionHistory;
import org.junit.jupiter.api.Test;

class ExecutionHistoryServiceTest {
    @Test
    void returnsChronologicalRecordsAndEmptyHistoryForUnknownOs() {
        var repository = mock(ExecutionHistoryRepositoryPort.class);
        var service = new ExecutionHistoryService(repository);
        UUID osId = UUID.randomUUID();
        var first = new ExecutionHistory(UUID.randomUUID(), osId, null, null,
                "note", "Primeiro", Instant.parse("2026-10-19T10:00:00Z"), null);
        var second = new ExecutionHistory(UUID.randomUUID(), osId, null, null,
                "note", "Segundo", first.createdAt().plusSeconds(1), null);
        when(repository.findByOsIdOrderByCreatedAt(osId)).thenReturn(List.of(first, second));
        assertThat(service.findByOsId(osId)).containsExactly(first, second);
        assertThat(service.findByOsId(UUID.randomUUID())).isEmpty();
    }
}
