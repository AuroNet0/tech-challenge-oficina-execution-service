package com.oficina.execution.infrastructure.web.response;

import com.oficina.execution.domain.enums.DiagnosticItemType;
import com.oficina.execution.domain.enums.PriceUnit;
import com.oficina.execution.domain.model.DiagnosticItem;

public record DiagnosticItemResponse(String itemId, String descricao, DiagnosticItemType tipo,
        String quantidade, PriceUnit unidadePreco, String precoPrevisto) {
    public static DiagnosticItemResponse from(DiagnosticItem item) {
        return new DiagnosticItemResponse(item.itemId(), item.descricao(), item.tipo(),
                item.quantidade().toPlainString(), item.unidadePreco(), item.precoPrevisto().toPlainString());
    }
}
