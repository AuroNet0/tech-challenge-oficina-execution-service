package com.oficina.execution.infrastructure.web.controller;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("@technicalAuthorization.repair(authentication, #id, 'REPAIR_READ')")
    public RepairResponse findById(@PathVariable UUID id) {
        return RepairResponse.from(useCase.findById(id));
    }

    @GetMapping
    @PreAuthorize("@technicalAuthorization.os(authentication, #osId, 'REPAIR_READ')")
    public RepairResponse findByOsId(@RequestParam UUID osId) {
        return RepairResponse.from(useCase.findByOsId(osId));
    }

    @PatchMapping("/{id}/observacoes")
    @PreAuthorize("@technicalAuthorization.repair(authentication, #id, 'REPAIR_WRITE')")
    public RepairResponse updateObservacoes(@PathVariable UUID id, @Valid @RequestBody UpdateObservacoesRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return RepairResponse.from(useCase.updateObservacoes(id, request.observacoes(), correlationId));
    }

    @PostMapping("/{id}/conclusao")
    @PreAuthorize("@technicalAuthorization.repair(authentication, #id, 'REPAIR_COMPLETE')")
    public RepairResponse complete(@PathVariable UUID id,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        return RepairResponse.from(useCase.complete(id, correlationId));
    }
}
