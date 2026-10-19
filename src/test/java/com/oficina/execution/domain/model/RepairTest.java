package com.oficina.execution.domain.model;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

class RepairTest {
    static Repair repair() {
        return Repair.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void createsPendingAndRequiresReferences() {
        var pending = repair();
        assertThat(pending.status()).isEqualTo(RepairStatus.PENDING);
        assertThat(pending.id()).isNotNull();
        assertThat(pending.startedAt()).isNull();
        assertThat(pending.completedAt()).isNull();
        assertThat(pending.version()).isNull();
        assertThat(pending.createdAt()).isEqualTo(pending.updatedAt());
        assertThatThrownBy(() -> Repair.create(null, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Repair.create(UUID.randomUUID(), null, UUID.randomUUID())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Repair.create(UUID.randomUUID(), UUID.randomUUID(), null)).isInstanceOf(DomainException.class);
    }

    @Test
    void startsOnlyThroughExplicitAuthorizationAndCompletes() {
        var pending = repair().updateObservacoes(" verificar ");
        assertThatThrownBy(pending::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> pending.fail("erro")).isInstanceOf(DomainException.class);
        var started = pending.authorizeAndStart();
        assertThat(started.status()).isEqualTo(RepairStatus.IN_PROGRESS);
        assertThat(started.startedAt()).isAfterOrEqualTo(started.createdAt());
        assertThat(started.observacoes()).isEqualTo("verificar");
        assertThat(pending.startedAt()).isNull();
        assertThatThrownBy(started::authorizeAndStart).isInstanceOf(DomainException.class);
        var edited = started.updateObservacoes(" executando ");
        assertThat(edited.startedAt()).isEqualTo(started.startedAt());
        var completed = edited.complete();
        assertThat(completed.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(completed.updatedAt()).isAfterOrEqualTo(completed.startedAt());
        assertThatThrownBy(completed::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(completed::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(completed::authorizeAndStart).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.fail("erro")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.updateObservacoes("x")).isInstanceOf(DomainException.class);
        assertThat(completed.cancellationOutcome()).isEqualTo(CancellationOutcome.COMPENSATION_FAILED);
    }

    @Test
    void cancelsOnlyBeforePhysicalWorkAndDistinguishesCompensation() {
        var pending = repair();
        assertThat(pending.cancellationOutcome()).isEqualTo(CancellationOutcome.CANCELLATION_ALLOWED);
        var cancelled = pending.cancel();
        assertThat(cancelled.status()).isEqualTo(RepairStatus.CANCELLED);
        assertThat(cancelled.cancellationOutcome()).isEqualTo(CancellationOutcome.CONFIRMED);
        assertThatThrownBy(cancelled::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(cancelled::authorizeAndStart).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cancelled.updateObservacoes("x")).isInstanceOf(DomainException.class);
        var started = pending.authorizeAndStart();
        assertThat(started.cancellationOutcome()).isEqualTo(CancellationOutcome.MANUAL_INTERVENTION_REQUIRED);
        assertThatThrownBy(started::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> started.fail(" ")).isInstanceOf(DomainException.class);
        var failed = started.fail(" falha mecanica ");
        assertThat(failed.status()).isEqualTo(RepairStatus.FAILED);
        assertThat(failed.observacoes()).isEqualTo("falha mecanica");
        assertThat(failed.startedAt()).isEqualTo(started.startedAt());
        assertThat(failed.completedAt()).isNull();
        assertThat(failed.cancellationOutcome()).isEqualTo(CancellationOutcome.MANUAL_INTERVENTION_REQUIRED);
        assertThatThrownBy(failed::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(failed::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(failed::authorizeAndStart).isInstanceOf(DomainException.class);
    }
}
