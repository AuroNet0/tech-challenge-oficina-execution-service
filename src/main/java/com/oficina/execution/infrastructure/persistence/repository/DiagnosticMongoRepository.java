package com.oficina.execution.infrastructure.persistence.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;
import com.oficina.execution.infrastructure.persistence.document.DiagnosticDocument;

public interface DiagnosticMongoRepository extends MongoRepository<DiagnosticDocument, String> {
    Optional<DiagnosticDocument> findByOsId(String osId);
    List<DiagnosticDocument> findBySagaId(String sagaId);
    boolean existsByOsId(String osId);
}
