package com.oficina.execution.application.port.out;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import com.oficina.execution.domain.model.Repair;

public interface RepairRepositoryPort {
    Repair save(Repair value);
    Optional<Repair> findById(UUID id);
    Optional<Repair> findByOsId(UUID osId);
    List<Repair> findBySagaId(UUID sagaId);
    boolean existsByOsId(UUID osId);
}
