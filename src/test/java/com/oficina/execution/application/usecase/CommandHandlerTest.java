package com.oficina.execution.application.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import com.oficina.execution.application.message.*;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommandHandlerTest {
    @Mock InboxRepositoryPort inbox;
    @Mock DiagnosticRepositoryPort diagnostics;
    @Mock RepairRepositoryPort repairs;
    @Mock ExecutionHistoryRepositoryPort history;
    @Mock EventRecorder events;
    CommandHandler handler;
    UUID osId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        handler = new CommandHandler(inbox, diagnostics, repairs, history, events);
    }

    CommandEnvelope command(String type) {
        CommandPayload payload = switch (type) {
            case "diagnostic.start.v1" -> new CommandPayload.DiagnosticStart(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "nota");
            case "repair.start.v1" -> new CommandPayload.RepairStart();
            default -> new CommandPayload.RepairCancel("falha");
        };
        return new CommandEnvelope(UUID.randomUUID(), type, Instant.now(), correlationId, sagaId, osId, "os-service", payload);
    }

    Diagnostic completed() {
        return Diagnostic.create(osId, sagaId).start().addItem(new DiagnosticItem("a", "Servico", DiagnosticItemType.SERVICO,
                BigDecimal.ONE, PriceUnit.HORA, BigDecimal.ONE)).complete();
    }

    @Test
    void queuesDiagnosticAndProcessesInboxWithoutStartedEvent() {
        var command = command("diagnostic.start.v1");
        when(diagnostics.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(handler.handle(command, "{}")).isEqualTo(InboxStatus.PROCESSED);
        verify(diagnostics).save(argThat(value -> value.status() == DiagnosticStatus.PENDING && value.observacoes().equals("nota")));
        verify(inbox).insert(argThat(value -> value.status() == InboxStatus.RECEIVED));
        verify(inbox).update(argThat(value -> value.status() == InboxStatus.PROCESSED && value.processedAt() != null));
        verifyNoInteractions(events);
    }

    @Test
    void deduplicatesSameIdAndEquivalentDiagnosticForSameOs() {
        var command = command("diagnostic.start.v1");
        var message = new InboxMessage(command.eventId(), command.eventType(), sagaId, osId, correlationId,
                "{}", InboxStatus.PROCESSED, Instant.now(), Instant.now(), null);
        when(inbox.findByEventId(command.eventId())).thenReturn(Optional.of(message));
        assertThat(handler.handle(command, "{}")).isEqualTo(InboxStatus.PROCESSED);
        verifyNoInteractions(diagnostics, repairs, history, events);
        var other = command("diagnostic.start.v1");
        when(diagnostics.findByOsId(osId)).thenReturn(Optional.of(completed()));
        assertThat(handler.handle(other, "{}")).isEqualTo(InboxStatus.PROCESSED);
        verify(diagnostics, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void rejectsMismatchedSagaWithoutPublishingAgainstWrongSaga() {
        var command = command("diagnostic.start.v1");
        when(diagnostics.findByOsId(osId)).thenReturn(Optional.of(Diagnostic.create(osId, UUID.randomUUID())));
        assertThat(handler.handle(command, "{}")).isEqualTo(InboxStatus.REJECTED);
        verify(inbox).update(argThat(value -> value.lastError().contains("sagaId")));
        verifyNoInteractions(events);
    }

    @Test
    void authorizesRepairOnlyAfterCompletedDiagnostic() {
        var diagnostic = completed();
        when(diagnostics.findByOsId(osId)).thenReturn(Optional.of(diagnostic));
        when(repairs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(handler.handle(command("repair.start.v1"), "{}")).isEqualTo(InboxStatus.PROCESSED);
        verify(repairs).save(argThat(value -> value.status() == RepairStatus.PENDING && value.diagnosticId().equals(diagnostic.id())));
        verifyNoInteractions(events);
    }

    @Test
    void missingOrIncompleteDiagnosticProducesFailureAndRejectedInbox() {
        assertThat(handler.handle(command("repair.start.v1"), "{}")).isEqualTo(InboxStatus.REJECTED);
        when(diagnostics.findByOsId(osId)).thenReturn(Optional.of(Diagnostic.create(osId, sagaId)));
        assertThat(handler.handle(command("repair.start.v1"), "{}")).isEqualTo(InboxStatus.REJECTED);
        verify(repairs, never()).save(any());
        verify(events, times(2)).record(eq(osId), eq("Execution"), eq(osId), eq(sagaId), eq(correlationId),
                eq("repair.failed.v1"), any(EventPayload.Failure.class));
    }

    @ParameterizedTest
    @EnumSource(RepairStatus.class)
    void compensationNeverPretendsToReversePhysicalWork(RepairStatus status) {
        var pending = Repair.create(osId, sagaId, UUID.randomUUID());
        Repair repair = switch (status) {
            case PENDING -> pending;
            case IN_PROGRESS -> pending.authorizeAndStart();
            case COMPLETED -> pending.authorizeAndStart().complete();
            case FAILED -> pending.authorizeAndStart().fail("falha");
            case CANCELLED -> pending.cancel();
        };
        when(repairs.findByOsId(osId)).thenReturn(Optional.of(repair));
        if (status == RepairStatus.PENDING) when(repairs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        handler.handle(command("repair.cancel.v1"), "{}");
        boolean safe = status == RepairStatus.PENDING || status == RepairStatus.CANCELLED;
        verify(events).record(eq(repair.id()), eq("Repair"), eq(osId), eq(sagaId), eq(correlationId),
                eq(safe ? "repair.cancelled.v1" : "repair.cancellation-failed.v1"), any());
        if (status != RepairStatus.PENDING) verify(repairs, never()).save(any());
    }
}
