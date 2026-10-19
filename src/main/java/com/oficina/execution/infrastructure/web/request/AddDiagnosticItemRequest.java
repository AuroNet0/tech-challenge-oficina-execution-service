package com.oficina.execution.infrastructure.web.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import com.oficina.execution.domain.enums.DiagnosticItemType;
import com.oficina.execution.domain.enums.PriceUnit;
import com.oficina.execution.domain.model.DiagnosticItem;

public record AddDiagnosticItemRequest(
        @NotBlank String itemId,
        @NotBlank String descricao,
        @NotNull DiagnosticItemType tipo,
        @NotBlank @Pattern(regexp = "[0-9]+(\\.[0-9]+)?") @DecimalMin(value = "0", inclusive = false) String quantidade,
        @NotNull PriceUnit unidadePreco,
        @NotBlank @Pattern(regexp = "[0-9]+(\\.[0-9]+)?") @DecimalMin("0") String precoPrevisto) {
    public DiagnosticItem toDomain() {
        return new DiagnosticItem(itemId, descricao, tipo, new BigDecimal(quantidade),
                unidadePreco, new BigDecimal(precoPrevisto));
    }
}
