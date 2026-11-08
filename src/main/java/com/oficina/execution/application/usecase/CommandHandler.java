package com.oficina.execution.application.usecase;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.execution.application.exception.InvalidCommandException;
import com.oficina.execution.application.message.*;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.exception.DomainException;
import com.oficina.execution.domain.model.*;

@Service
public class CommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);
    private final InboxRepositoryPort inbox;
    private final DiagnosticRepositoryPort diagnostics;
    private final RepairRepositoryPort repairs;
    private final ExecutionHistoryRepositoryPort history;
    private final EventRecorder events;

    public CommandHandler(InboxRepositoryPort inbox, DiagnosticRepositoryPort diagnostics, RepairRepositoryPort repairs,
            ExecutionHistoryRepositoryPort history, EventRecorder events) {
        this.inbox = inbox;
        this.diagnostics = diagnostics;
        this.repairs = repairs;
        this.history = history;
        this.events = events;
    }

    @Transactional
    public InboxStatus handle(CommandEnvelope command, String rawBody) {
        var existing = inbox.findByEventId(command.eventId());
        if (existing.isPresent()) {
            InboxMessage message = existing.get();
            if (!message.osId().equals(command.osId()) || !message.sagaId().equals(command.sagaId())
                    || !message.eventType().equals(command.eventType()))
                throw new InvalidCommandException("eventId reused with incompatible identifiers");
            if (message.status() == InboxStatus.RECEIVED) throw new IllegalStateException("Inbox ainda nao finalizada");
            log(command, "duplicate");
            return message.status();
        }
        InboxMessage message = new InboxMessage(command.eventId(), command.eventType(), command.sagaId(),
                command.osId(), command.correlationId(), rawBody, InboxStatus.RECEIVED, Instant.now(), null, null);
        inbox.insert(message);
        InboxStatus result = InboxStatus.PROCESSED;
        String error = null;
        try {
            switch (command.eventType()) {
                case "diagnostic.start.v1" -> startDiagnostic(command);
                case "repair.start.v1" -> startRepair(command);
                case "repair.cancel.v1" -> cancelRepair(command);
                default -> throw new InvalidCommandException("Unsupported command");
            }
        } catch (InvalidCommandException exception) {
            result = InboxStatus.REJECTED;
            error = exception.getMessage();
        } catch (DomainException exception) {
            result = InboxStatus.REJECTED;
            error = exception.getMessage();
            String type = command.eventType().equals("diagnostic.start.v1") ? "diagnostic.failed.v1"
                    : command.eventType().equals("repair.cancel.v1") ? "repair.cancellation-failed.v1" : "repair.failed.v1";
            events.record(command.osId(), "Execution", command.osId(), command.sagaId(), command.correlationId(),
                    type, new EventPayload.Failure(error));
            recordHistory(command, null, null, type, error);
        }
        inbox.update(message.finish(result, error));
        log(command, result.name());
        return result;
    }

    private void startDiagnostic(CommandEnvelope command) {
        var existing = diagnostics.findByOsId(command.osId());
        if (existing.isPresent()) {
            requireSaga(existing.get().sagaId(), command);
            return;
        }
        var payload = (CommandPayload.DiagnosticStart) command.payload();
        Diagnostic diagnostic = Diagnostic.create(command.osId(), command.sagaId())
                .updateObservacoes(payload.observacoes());
        diagnostic = diagnostics.save(diagnostic);
        recordHistory(command, diagnostic.id(), null, "diagnostic.queued", "Diagnostico aguardando inicio operacional");
    }

    private void startRepair(CommandEnvelope command) {
        Diagnostic diagnostic = diagnostics.findByOsId(command.osId())
                .orElseThrow(() -> new DomainException("Diagnostico inexistente"));
        requireSaga(diagnostic.sagaId(), command);
        if (diagnostic.status() != DiagnosticStatus.COMPLETED)
            throw new DomainException("Diagnostico deve estar concluido");
        var existing = repairs.findByOsId(command.osId());
        Repair repair = existing.orElseGet(() ->
                Repair.create(command.osId(), command.sagaId(), diagnostic.id()));
        requireSaga(repair.sagaId(), command);
        if (!repair.diagnosticId().equals(diagnostic.id()))
            throw new InvalidCommandException("Reparo referencia outro diagnostico");
        if (existing.isPresent()) return;
        repair = repairs.save(repair);
        recordHistory(command, diagnostic.id(), repair.id(), "repair.queued", "Reparo autorizado pelo OS Service aguardando inicio operacional");
    }

    private void cancelRepair(CommandEnvelope command) {
        Repair repair = repairs.findByOsId(command.osId())
                .orElseThrow(() -> new DomainException("Reparo inexistente; cancelamento nao confirmado"));
        requireSaga(repair.sagaId(), command);
        boolean safe = repair.cancellationOutcome() == CancellationOutcome.CANCELLATION_ALLOWED
                || repair.cancellationOutcome() == CancellationOutcome.CONFIRMED;
        if (repair.status() == RepairStatus.PENDING) repair = repairs.save(repair.cancel());
        String type = safe ? "repair.cancelled.v1" : "repair.cancellation-failed.v1";
        String description = safe ? "Cancelamento confirmado antes de trabalho fisico"
                : "Cancelamento nao confirmado: trabalho fisico exige intervencao manual";
        recordHistory(command, repair.diagnosticId(), repair.id(), type, description);
        events.record(repair.id(), "Repair", command.osId(), command.sagaId(), command.correlationId(), type,
                safe ? new EventPayload.Empty() : new EventPayload.Failure(description));
    }

    private void requireSaga(UUID sagaId, CommandEnvelope command) {
        if (!sagaId.equals(command.sagaId())) throw new InvalidCommandException("sagaId incompativel");
    }

    private void recordHistory(CommandEnvelope command, UUID diagnosticId, UUID repairId, String type, String description) {
        history.save(ExecutionHistory.create(command.osId(), diagnosticId, repairId, type, description, command.correlationId()));
    }

    private void log(CommandEnvelope command, String result) {
        LOGGER.info("command eventId={} eventType={} sagaId={} osId={} correlationId={} result={}",
                command.eventId(), command.eventType(), command.sagaId(), command.osId(), command.correlationId(), result);
    }
}
