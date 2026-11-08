package com.oficina.execution.infrastructure.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.oficina.execution.application.port.in.ExecutionHistoryUseCase;
import com.oficina.execution.infrastructure.web.response.ExecutionHistoryResponse;

@RestController
@RequestMapping("/api/v1/execucoes/historico")
public class ExecutionHistoryController {
    private final ExecutionHistoryUseCase useCase;

    public ExecutionHistoryController(ExecutionHistoryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    @PreAuthorize("@technicalAuthorization.os(authentication, #osId, 'HISTORY_READ')")
    public List<ExecutionHistoryResponse> findByOsId(@RequestParam UUID osId) {
        return useCase.findByOsId(osId).stream().map(ExecutionHistoryResponse::from).toList();
    }
}
