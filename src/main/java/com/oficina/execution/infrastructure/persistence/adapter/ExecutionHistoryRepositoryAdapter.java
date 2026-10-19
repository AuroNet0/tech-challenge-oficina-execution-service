package com.oficina.execution.infrastructure.persistence.adapter;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import com.oficina.execution.application.port.out.ExecutionHistoryRepositoryPort;
import com.oficina.execution.domain.model.ExecutionHistory;
import com.oficina.execution.infrastructure.persistence.mapper.ExecutionHistoryMapper;
import com.oficina.execution.infrastructure.persistence.repository.ExecutionHistoryMongoRepository;

@Repository
public class ExecutionHistoryRepositoryAdapter implements ExecutionHistoryRepositoryPort {
    private final ExecutionHistoryMongoRepository repository;

    public ExecutionHistoryRepositoryAdapter(ExecutionHistoryMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public ExecutionHistory save(ExecutionHistory value) {
        return ExecutionHistoryMapper.toDomain(repository.insert(ExecutionHistoryMapper.toDocument(value)));
    }

    @Override
    public List<ExecutionHistory> findByOsIdOrderByCreatedAt(UUID osId) {
        return repository.findByOsIdOrderByCreatedAtAscIdAsc(osId.toString()).stream()
                .map(ExecutionHistoryMapper::toDomain).toList();
    }

}
