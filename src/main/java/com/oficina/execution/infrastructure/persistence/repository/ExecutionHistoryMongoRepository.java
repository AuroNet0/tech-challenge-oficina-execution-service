package com.oficina.execution.infrastructure.persistence.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.oficina.execution.infrastructure.persistence.document.ExecutionHistoryDocument;

public interface ExecutionHistoryMongoRepository extends MongoRepository<ExecutionHistoryDocument, String> {
    List<ExecutionHistoryDocument> findByOsIdOrderByCreatedAtAscIdAsc(String osId);
}
