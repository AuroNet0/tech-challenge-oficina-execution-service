package com.oficina.execution.infrastructure.persistence.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;
import com.oficina.execution.infrastructure.persistence.document.RepairDocument;

public interface RepairMongoRepository extends MongoRepository<RepairDocument, String> {
    Optional<RepairDocument> findByOsId(String osId);
    List<RepairDocument> findBySagaId(String sagaId);
    boolean existsByOsId(String osId);
}
