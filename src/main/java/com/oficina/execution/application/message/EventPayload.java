package com.oficina.execution.application.message;

import java.util.List;
import com.oficina.execution.domain.model.DiagnosticItem;

public sealed interface EventPayload {
    record Empty() implements EventPayload {}
    record Failure(String reason) implements EventPayload {}
    record RepairCompleted(String repairId) implements EventPayload {}
    record DiagnosticCompleted(List<Item> items) implements EventPayload {
        public static DiagnosticCompleted from(List<DiagnosticItem> items) {
            return new DiagnosticCompleted(items.stream().map(item -> new Item(item.itemId(), item.descricao(),
                    item.tipo().name(), item.quantidade().toPlainString(), item.unidadePreco().name(),
                    item.precoPrevisto().toPlainString())).toList());
        }
    }
    record Item(String itemId, String descricao, String tipo, String quantidade,
            String unidadePreco, String precoPrevisto) {}
}
