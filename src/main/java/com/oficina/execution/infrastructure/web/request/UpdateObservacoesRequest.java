package com.oficina.execution.infrastructure.web.request;

import jakarta.validation.constraints.NotNull;

public record UpdateObservacoesRequest(@NotNull String observacoes) {
}
