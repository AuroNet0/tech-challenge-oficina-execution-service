package com.oficina.execution.infrastructure.web.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.oficina.execution.application.port.in.DiagnosticUseCase;
import com.oficina.execution.infrastructure.web.request.UpdateObservacoesRequest;
import com.oficina.execution.infrastructure.web.request.AddDiagnosticItemRequest;
import com.oficina.execution.infrastructure.web.response.DiagnosticResponse;

@RestController
@RequestMapping("/api/v1/diagnosticos")
public class DiagnosticController {
    private final DiagnosticUseCase useCase;

    public DiagnosticController(DiagnosticUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/{id}")
    public DiagnosticResponse findById(@PathVariable UUID id) {
        return DiagnosticResponse.from(useCase.findById(id));
    }

    @GetMapping
    public DiagnosticResponse findByOsId(@RequestParam UUID osId) {
        return DiagnosticResponse.from(useCase.findByOsId(osId));
    }

    @PostMapping("/{id}/itens")
    public DiagnosticResponse addItem(@PathVariable UUID id, @Valid @RequestBody AddDiagnosticItemRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return DiagnosticResponse.from(useCase.addItem(id, request.toDomain(), correlationId));
    }

    @PatchMapping("/{id}/observacoes")
    public DiagnosticResponse updateObservacoes(@PathVariable UUID id, @Valid @RequestBody UpdateObservacoesRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return DiagnosticResponse.from(useCase.updateObservacoes(id, request.observacoes(), correlationId));
    }

    @PostMapping("/{id}/conclusao")
    public DiagnosticResponse complete(@PathVariable UUID id,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return DiagnosticResponse.from(useCase.complete(id, correlationId));
    }
}
