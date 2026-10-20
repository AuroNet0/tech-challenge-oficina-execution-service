package com.oficina.execution.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import com.oficina.execution.application.exception.ResourceNotFoundException;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.model.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class RepairServiceTest {
    @Mock RepairRepositoryPort repository;
    @Mock ExecutionHistoryRepositoryPort history;
    @Mock EventRecorder events;
    RepairService service;
    Repair repair;
    UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new RepairService(repository, history, events);
        repair = Repair.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).authorizeAndStart();
    }

    @Test
    void queriesByIdAndOsId() {
        when(repository.findById(repair.id())).thenReturn(Optional.of(repair));
        when(repository.findByOsId(repair.osId())).thenReturn(Optional.of(repair));
        assertThat(service.findById(repair.id())).isEqualTo(repair);
        assertThat(service.findByOsId(repair.osId())).isEqualTo(repair);
        verifyNoInteractions(history);
    }

    @Test
    void reportsMissingResources() {
        assertThatThrownBy(() -> service.findById(repair.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.findByOsId(repair.osId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.complete(repair.id(), correlationId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.updateObservacoes(repair.id(), "nota", null)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void updatesObservationsAndRecordsReferencesAndCorrelation() {
        when(repository.findById(repair.id())).thenReturn(Optional.of(repair));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var updated = service.updateObservacoes(repair.id(), " observacao ", correlationId);
        assertThat(updated.observacoes()).isEqualTo("observacao");
        verify(history).save(argThat(entry -> entry.osId().equals(repair.osId())
                && entry.repairId().equals(repair.id())
                && entry.correlationId().equals(correlationId)
                && entry.eventType().equals("repair.observations-updated")));
    }

    @Test
    void completesAndRecordsHistoryAndOutbox() {
        
        when(repository.findById(repair.id())).thenReturn(Optional.of(repair));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var completed = service.complete(repair.id(), correlationId);
        assertThat(completed.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        verify(history).save(argThat(entry -> entry.eventType().equals("repair.completed.v1")
                && entry.correlationId().equals(correlationId)));
        verify(events).record(eq(repair.id()), eq("Repair"), eq(repair.osId()),
                eq(repair.sagaId()), eq(correlationId), eq("repair.completed.v1"), any());
    }

    @Test
    void rejectsCompletionBeforeAuthorization() {
        repair = Repair.create(repair.osId(), repair.sagaId(), repair.diagnosticId());
        when(repository.findById(repair.id())).thenReturn(Optional.of(repair));
        assertThatThrownBy(() -> service.complete(repair.id(), null)).isInstanceOf(DomainException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void preservesVersionConflictWithoutWritingHistoryOrRetrying() {
        when(repository.findById(repair.id())).thenReturn(Optional.of(repair));
        when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));
        assertThatThrownBy(() -> service.updateObservacoes(repair.id(), "nota", correlationId))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(repository, times(1)).save(any());
        verifyNoInteractions(history);
    }

}
