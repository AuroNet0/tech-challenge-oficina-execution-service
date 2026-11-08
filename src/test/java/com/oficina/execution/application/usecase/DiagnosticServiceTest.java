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
class DiagnosticServiceTest {
    @Mock DiagnosticRepositoryPort repository;
    @Mock ExecutionHistoryRepositoryPort history;
    @Mock EventRecorder events;
    DiagnosticService service;
    Diagnostic diagnostic;
    UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new DiagnosticService(repository, history, events);
        diagnostic = Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start();
    }

    private DiagnosticItem item() {
        return new DiagnosticItem("item", "Servico", DiagnosticItemType.SERVICO,
                new BigDecimal("1.500"), PriceUnit.HORA, new BigDecimal("123.4500"));
    }

    @Test
    void queriesByIdAndOsId() {
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        when(repository.findByOsId(diagnostic.osId())).thenReturn(Optional.of(diagnostic));
        assertThat(service.findById(diagnostic.id())).isEqualTo(diagnostic);
        assertThat(service.findByOsId(diagnostic.osId())).isEqualTo(diagnostic);
        verifyNoInteractions(history);
    }

    @Test
    void reportsMissingResources() {
        assertThatThrownBy(() -> service.findById(diagnostic.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.findByOsId(diagnostic.osId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.complete(diagnostic.id(), correlationId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.updateObservacoes(diagnostic.id(), "nota", null)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void updatesObservationsAndRecordsReferencesAndCorrelation() {
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var updated = service.updateObservacoes(diagnostic.id(), " observacao ", correlationId);
        assertThat(updated.observacoes()).isEqualTo("observacao");
        verify(history).save(argThat(entry -> entry.osId().equals(diagnostic.osId())
                && entry.diagnosticId().equals(diagnostic.id())
                && entry.correlationId().equals(correlationId)
                && entry.eventType().equals("diagnostic.observations-updated")));
    }

    @Test
    void completesAndRecordsHistoryAndOutbox() {
        diagnostic = diagnostic.addItem(item());
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var completed = service.complete(diagnostic.id(), correlationId);
        assertThat(completed.status()).isEqualTo(DiagnosticStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        verify(history).save(argThat(entry -> entry.eventType().equals("diagnostic.completed.v1")
                && entry.correlationId().equals(correlationId)));
        verify(events).record(eq(diagnostic.id()), eq("Diagnostic"), eq(diagnostic.osId()),
                eq(diagnostic.sagaId()), eq(correlationId), eq("diagnostic.completed.v1"), any());
    }

    @Test
    void rejectsCompletionWithoutItems() {
        
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        assertThatThrownBy(() -> service.complete(diagnostic.id(), null)).isInstanceOf(DomainException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void preservesVersionConflictWithoutWritingHistoryOrRetrying() {
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("stale"));
        assertThatThrownBy(() -> service.updateObservacoes(diagnostic.id(), "nota", correlationId))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(repository, times(1)).save(any());
        verifyNoInteractions(history);
    }

    @Test
    void addsItemWithoutCalculatingQuote() {
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var updated = service.addItem(diagnostic.id(), item(), correlationId);
        assertThat(updated.items()).containsExactly(item());
        assertThat(updated.items().getFirst().precoPrevisto()).isEqualTo(new BigDecimal("123.4500"));
        verify(history).save(argThat(entry -> entry.eventType().equals("diagnostic.item-added")));
    }

    @Test
    void rejectsInvalidAndDuplicateItems() {
        diagnostic = diagnostic.addItem(item());
        when(repository.findById(diagnostic.id())).thenReturn(Optional.of(diagnostic));
        assertThatThrownBy(() -> service.addItem(diagnostic.id(), item(), null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.addItem(diagnostic.id(), null, null)).isInstanceOf(DomainException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(history);
    }

}
