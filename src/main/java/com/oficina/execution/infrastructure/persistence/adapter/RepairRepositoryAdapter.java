package com.oficina.execution.infrastructure.persistence.adapter;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import com.oficina.execution.application.port.out.RepairRepositoryPort;
import com.oficina.execution.domain.model.Repair;
import com.oficina.execution.infrastructure.persistence.mapper.RepairMapper;
import com.oficina.execution.infrastructure.persistence.repository.RepairMongoRepository;

@Repository
public class RepairRepositoryAdapter implements RepairRepositoryPort {
    private final RepairMongoRepository repository;

    public RepairRepositoryAdapter(RepairMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public Repair save(Repair value) {
        return RepairMapper.toDomain(repository.save(RepairMapper.toDocument(value)));
    }

    @Override
    public Optional<Repair> findById(UUID id) {
        return repository.findById(id.toString()).map(RepairMapper::toDomain);
    }

    @Override
    public Optional<Repair> findByOsId(UUID osId) {
        return repository.findByOsId(osId.toString()).map(RepairMapper::toDomain);
    }

    @Override
    public List<Repair> findBySagaId(UUID sagaId) {
        return repository.findBySagaId(sagaId.toString()).stream().map(RepairMapper::toDomain).toList();
    }

    @Override
    public boolean existsByOsId(UUID osId) {
        return repository.existsByOsId(osId.toString());
    }

}
