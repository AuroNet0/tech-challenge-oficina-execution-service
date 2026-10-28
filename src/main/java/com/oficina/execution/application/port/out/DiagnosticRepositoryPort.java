package com.oficina.execution.application.port.out;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import com.oficina.execution.domain.model.Diagnostic;

public interface DiagnosticRepositoryPort {
    List<Diagnostic> findPending();
    Diagnostic save(Diagnostic value);
    Optional<Diagnostic> findById(UUID id);
    Optional<Diagnostic> findByOsId(UUID osId);
    List<Diagnostic> findBySagaId(UUID sagaId);
    boolean existsByOsId(UUID osId);
}
