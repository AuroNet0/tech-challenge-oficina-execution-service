package com.oficina.execution.infrastructure.web.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.oficina.execution.application.port.in.RepairUseCase;
import com.oficina.execution.infrastructure.web.request.UpdateObservacoesRequest;
import com.oficina.execution.infrastructure.web.response.RepairResponse;

@RestController
@RequestMapping("/api/v1/reparos")
public class RepairController {
    private final RepairUseCase useCase;

    public RepairController(RepairUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/{id}")
    public RepairResponse findById(@PathVariable UUID id) {
        return RepairResponse.from(useCase.findById(id));
    }

    @GetMapping
    public RepairResponse findByOsId(@RequestParam UUID osId) {
        return RepairResponse.from(useCase.findByOsId(osId));
    }

    @PatchMapping("/{id}/observacoes")
    public RepairResponse updateObservacoes(@PathVariable UUID id, @Valid @RequestBody UpdateObservacoesRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return RepairResponse.from(useCase.updateObservacoes(id, request.observacoes(), correlationId));
    }

    @PostMapping("/{id}/conclusao")
    public RepairResponse complete(@PathVariable UUID id,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return RepairResponse.from(useCase.complete(id, correlationId));
    }
}
