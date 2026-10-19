package com.oficina.execution.domain.model;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DiagnosticTest {
    static DiagnosticItem item() {
        return new DiagnosticItem("  item-1 ", " Troca de oleo ", DiagnosticItemType.SERVICO,
                new BigDecimal("1.250"), PriceUnit.HORA, new BigDecimal("120.00"));
    }

    static Diagnostic diagnostic() {
        return Diagnostic.create(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void createsPendingAndRejectsMissingIdentifiers() {
        var diagnostic = diagnostic();
        assertThat(diagnostic.id()).isNotNull();
        assertThat(diagnostic.status()).isEqualTo(DiagnosticStatus.PENDING);
        assertThat(diagnostic.createdAt()).isEqualTo(diagnostic.updatedAt());
        assertThat(diagnostic.completedAt()).isNull();
        assertThat(diagnostic.version()).isNull();
        assertThatThrownBy(() -> Diagnostic.create(null, UUID.randomUUID())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Diagnostic.create(UUID.randomUUID(), null)).isInstanceOf(DomainException.class);
    }

    @Test
    void completesWithItemsAndPreservesImmutableSnapshots() {
        var pending = diagnostic().updateObservacoes("  verificar motor  ");
        var started = pending.start();
        var populated = started.addItem(item());
        var completed = populated.complete();
        assertThat(pending.status()).isEqualTo(DiagnosticStatus.PENDING);
        assertThat(started.items()).isEmpty();
        assertThat(completed.status()).isEqualTo(DiagnosticStatus.COMPLETED);
        assertThat(completed.observacoes()).isEqualTo("verificar motor");
        assertThat(completed.completedAt()).isEqualTo(completed.updatedAt()).isAfterOrEqualTo(completed.createdAt());
        assertThat(completed.items()).containsExactly(item());
        assertThatThrownBy(() -> completed.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> completed.addItem(item())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.updateObservacoes("alterado")).isInstanceOf(DomainException.class);
        assertThatThrownBy(completed::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(completed::start).isInstanceOf(DomainException.class);
        assertThatThrownBy(completed::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.fail("erro")).isInstanceOf(DomainException.class);
    }

    @Test
    void enforcesTransitionOrderAndItems() {
        var pending = diagnostic();
        assertThatThrownBy(pending::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> pending.fail("erro")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> pending.addItem(item())).isInstanceOf(DomainException.class);
        var started = pending.start();
        assertThatThrownBy(started::start).isInstanceOf(DomainException.class);
        assertThatThrownBy(started::complete).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> started.addItem(null)).isInstanceOf(DomainException.class);
        var populated = started.addItem(item());
        assertThatThrownBy(() -> populated.addItem(item())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> started.fail(" ")).isInstanceOf(DomainException.class);
        var failed = started.fail(" falha tecnica ");
        assertThat(failed.status()).isEqualTo(DiagnosticStatus.FAILED);
        assertThat(failed.observacoes()).isEqualTo("falha tecnica");
        assertThatThrownBy(failed::start).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> failed.updateObservacoes("x")).isInstanceOf(DomainException.class);
        assertThatThrownBy(failed::cancel).isInstanceOf(DomainException.class);
        assertThat(failed.observacoes()).isEqualTo("falha tecnica");
        assertThat(started.cancel().status()).isEqualTo(DiagnosticStatus.CANCELLED);
        var cancelled = pending.cancel();
        assertThat(cancelled.status()).isEqualTo(DiagnosticStatus.CANCELLED);
        assertThatThrownBy(cancelled::cancel).isInstanceOf(DomainException.class);
        assertThatThrownBy(cancelled::start).isInstanceOf(DomainException.class);
    }

    @Test
    void normalizesTextAndPreservesDecimalPrecisionAndScale() {
        var item = item();
        assertThat(item.itemId()).isEqualTo("item-1");
        assertThat(item.descricao()).isEqualTo("Troca de oleo");
        assertThat(item.quantidade()).isEqualTo(new BigDecimal("1.250"));
        assertThat(item.precoPrevisto()).isEqualTo(new BigDecimal("120.00"));
        assertThat(new DiagnosticItem("a", "b", DiagnosticItemType.PECA, BigDecimal.ONE,
                PriceUnit.KIT, BigDecimal.ZERO).precoPrevisto()).isEqualTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.001"})
    void rejectsNonPositiveQuantity(String quantity) {
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", DiagnosticItemType.MATERIAL,
                new BigDecimal(quantity), PriceUnit.UNIDADE, BigDecimal.ONE)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsEmptyText(String value) {
        assertThatThrownBy(() -> new DiagnosticItem(value, "b", DiagnosticItemType.PECA,
                BigDecimal.ONE, PriceUnit.UNIDADE, BigDecimal.ONE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DiagnosticItem("a", value, DiagnosticItemType.PECA,
                BigDecimal.ONE, PriceUnit.UNIDADE, BigDecimal.ONE)).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsMissingFieldsAndNegativePrice() {
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", null, BigDecimal.ONE, PriceUnit.HORA, BigDecimal.ONE))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", DiagnosticItemType.PECA, null, PriceUnit.HORA, BigDecimal.ONE))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", DiagnosticItemType.PECA, BigDecimal.ONE, null, BigDecimal.ONE))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", DiagnosticItemType.PECA, BigDecimal.ONE, PriceUnit.HORA, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DiagnosticItem("a", "b", DiagnosticItemType.PECA, BigDecimal.ONE, PriceUnit.HORA, new BigDecimal("-0.01")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void defensivelyCopiesInputListAndValidatesReconstruction() {
        var diagnostic = diagnostic();
        var items = new ArrayList<>(List.of(item()));
        var restored = new Diagnostic(diagnostic.id(), diagnostic.osId(), diagnostic.sagaId(),
                DiagnosticStatus.IN_PROGRESS, null, items, diagnostic.createdAt(), diagnostic.updatedAt(), null, 2L);
        items.clear();
        assertThat(restored.items()).hasSize(1);
        assertThatThrownBy(() -> new Diagnostic(diagnostic.id(), diagnostic.osId(), diagnostic.sagaId(),
                DiagnosticStatus.COMPLETED, null, List.of(), diagnostic.createdAt(), diagnostic.updatedAt(), null, 0L))
                .isInstanceOf(DomainException.class);
    }
}
