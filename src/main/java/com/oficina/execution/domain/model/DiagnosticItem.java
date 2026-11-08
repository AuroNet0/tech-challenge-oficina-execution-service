package com.oficina.execution.domain.model;

import java.math.BigDecimal;
import com.oficina.execution.domain.enums.DiagnosticItemType;
import com.oficina.execution.domain.enums.PriceUnit;
import com.oficina.execution.domain.exception.DomainException;

public record DiagnosticItem(String itemId, String descricao, DiagnosticItemType tipo,
        BigDecimal quantidade, PriceUnit unidadePreco, BigDecimal precoPrevisto) {
    public DiagnosticItem {
        itemId = DomainValidation.text(itemId, "itemId");
        descricao = DomainValidation.text(descricao, "descricao");
        DomainValidation.required(tipo, "tipo");
        DomainValidation.required(unidadePreco, "unidadePreco");
        DomainValidation.required(quantidade, "quantidade");
        DomainValidation.required(precoPrevisto, "precoPrevisto");
        if (quantidade.signum() <= 0) throw new DomainException("quantidade must be positive");
        if (precoPrevisto.signum() < 0) throw new DomainException("precoPrevisto must not be negative");
    }
}
