package com.oficina.execution.infrastructure.persistence.adapter;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import com.oficina.execution.application.port.out.DiagnosticRepositoryPort;
import com.oficina.execution.domain.model.Diagnostic;
import com.oficina.execution.infrastructure.persistence.mapper.DiagnosticMapper;
import com.oficina.execution.infrastructure.persistence.repository.DiagnosticMongoRepository;

@Repository
public class DiagnosticRepositoryAdapter implements DiagnosticRepositoryPort {
    private final DiagnosticMongoRepository repository;

    public DiagnosticRepositoryAdapter(DiagnosticMongoRepository repository) {
        this.repository = repository;
    }

    @Override
    public Diagnostic save(Diagnostic value) {
        return DiagnosticMapper.toDomain(repository.save(DiagnosticMapper.toDocument(value)));
    }

    @Override
    public Optional<Diagnostic> findById(UUID id) {
        return repository.findById(id.toString()).map(DiagnosticMapper::toDomain);
    }

    @Override
    public Optional<Diagnostic> findByOsId(UUID osId) {
        return repository.findByOsId(osId.toString()).map(DiagnosticMapper::toDomain);
    }

    @Override
    public List<Diagnostic> findBySagaId(UUID sagaId) {
        return repository.findBySagaId(sagaId.toString()).stream().map(DiagnosticMapper::toDomain).toList();
    }

    @Override
    public boolean existsByOsId(UUID osId) {
        return repository.existsByOsId(osId.toString());
    }

}
