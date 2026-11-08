package com.oficina.execution.infrastructure.web.controller;

import com.oficina.execution.application.port.in.OperationalQueueUseCase;
import com.oficina.execution.application.port.in.OperationalQueueUseCase.WorkItem;
import com.oficina.execution.infrastructure.security.TechnicalAuthorization;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalQueueController {
    private final OperationalQueueUseCase queue;
    private final TechnicalAuthorization authorization;

    public OperationalQueueController(OperationalQueueUseCase queue, TechnicalAuthorization authorization) {
        this.queue = queue;
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/execucoes/fila")
    @PreAuthorize("hasRole('MECANICO')")
    public List<WorkItem> pending(Authentication actor) {
        return queue.pending().stream()
                .filter(item -> authorization.os(actor, item.osId(), item.type().name() + "_READ")).toList();
    }
}
