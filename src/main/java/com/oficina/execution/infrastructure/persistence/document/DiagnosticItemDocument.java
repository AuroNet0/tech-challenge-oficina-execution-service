package com.oficina.execution.infrastructure.persistence.document;

import java.math.BigDecimal;
import com.oficina.execution.domain.enums.DiagnosticItemType;
import com.oficina.execution.domain.enums.PriceUnit;
import com.oficina.execution.domain.model.DiagnosticItem;

public record DiagnosticItemDocument(String itemId, String descricao, DiagnosticItemType tipo,
        String quantidade, PriceUnit unidadePreco, String precoPrevisto) {
    public static DiagnosticItemDocument fromDomain(DiagnosticItem value) {
        return new DiagnosticItemDocument(value.itemId(), value.descricao(), value.tipo(),
                value.quantidade().toPlainString(), value.unidadePreco(), value.precoPrevisto().toPlainString());
    }

    public DiagnosticItem toDomain() {
        return new DiagnosticItem(itemId, descricao, tipo, new BigDecimal(quantidade),
                unidadePreco, new BigDecimal(precoPrevisto));
    }
}
