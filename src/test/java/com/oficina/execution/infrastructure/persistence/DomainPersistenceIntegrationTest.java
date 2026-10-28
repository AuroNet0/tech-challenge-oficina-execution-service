package com.oficina.execution.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.*;
import com.oficina.execution.infrastructure.persistence.document.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class DomainPersistenceIntegrationTest {
    @Container
    static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("execution_domain_test"));
    }

    @Autowired DiagnosticRepositoryPort diagnostics;
    @Autowired RepairRepositoryPort repairs;
    @Autowired ExecutionHistoryRepositoryPort history;
    @Autowired MongoTemplate mongo;
    @Autowired MongoDatabaseFactory databaseFactory;

    @BeforeEach
    void cleanDocumentsPreservingIndexes() {
        mongo.remove(new Query(), DiagnosticDocument.class);
        mongo.remove(new Query(), RepairDocument.class);
        mongo.remove(new Query(), ExecutionHistoryDocument.class);
    }

    private DiagnosticItem item() {
        return new DiagnosticItem("item-1", "Servico", DiagnosticItemType.SERVICO,
                new BigDecimal("1.250"), PriceUnit.HORA,
                new BigDecimal("1234567890123456789012345678901234567890.00100"));
    }

    @Test
    void roundTripsDiagnosticItemsAllFieldsAndDecimalStrings() {
        var original = Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start()
                .addItem(item()).updateObservacoes("nota").complete();
        var saved = diagnostics.save(original);
        assertThat(saved.version()).isZero();
        assertThat(saved).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
        assertThat(diagnostics.findById(saved.id())).contains(saved);
        assertThat(diagnostics.findByOsId(saved.osId())).contains(saved);
        assertThat(diagnostics.findBySagaId(saved.sagaId())).containsExactly(saved);
        assertThat(diagnostics.existsByOsId(saved.osId())).isTrue();
        assertThat(diagnostics.existsByOsId(UUID.randomUUID())).isFalse();
        assertThat(diagnostics.findById(UUID.randomUUID())).isEmpty();
        assertThat(diagnostics.findByOsId(UUID.randomUUID())).isEmpty();
        assertThat(diagnostics.findBySagaId(UUID.randomUUID())).isEmpty();
        Document raw = mongo.getCollection("diagnostics").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("_id")).isEqualTo(saved.id().toString());
        assertThat(raw.getString("osId")).isEqualTo(saved.osId().toString());
        Document rawItem = raw.getList("items", Document.class).getFirst();
        assertThat(rawItem.getString("quantidade")).isEqualTo("1.250");
        assertThat(rawItem.getString("precoPrevisto")).isEqualTo(item().precoPrevisto().toPlainString());
        assertThat(saved.items().getFirst().precoPrevisto()).isEqualTo(item().precoPrevisto());
    }

    @Test
    void roundTripsRepairReferencesAuthorizationAndTimestamps() {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().addItem(item()).complete());
        var pending = repairs.save(Repair.create(diagnostic.osId(), diagnostic.sagaId(), diagnostic.id()));
        assertThat(pending.version()).isZero();
        assertThat(repairs.findById(pending.id())).contains(pending);
        assertThat(repairs.findByOsId(pending.osId())).contains(pending);
        var saved = repairs.save(pending.authorizeAndStart().updateObservacoes("autorizado").complete());
        assertThat(saved.version()).isEqualTo(1L);
        assertThat(saved.diagnosticId()).isEqualTo(diagnostic.id());
        assertThat(saved.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(repairs.findById(saved.id())).contains(saved);
        assertThat(repairs.findBySagaId(saved.sagaId())).containsExactly(saved);
        assertThat(repairs.existsByOsId(saved.osId())).isTrue();
        assertThat(repairs.existsByOsId(UUID.randomUUID())).isFalse();
        assertThat(repairs.findById(UUID.randomUUID())).isEmpty();
        assertThat(repairs.findByOsId(UUID.randomUUID())).isEmpty();
        assertThat(repairs.findBySagaId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void preservesFailureAndCancellationStates() {
        var failed = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().fail("falha"));
        assertThat(diagnostics.findById(failed.id())).contains(failed);
        var cancelled = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).cancel());
        assertThat(diagnostics.findById(cancelled.id())).contains(cancelled);
        var repair = repairs.save(Repair.create(UUID.randomUUID(), UUID.randomUUID(), failed.id()).authorizeAndStart().fail("falha fisica"));
        assertThat(repairs.findById(repair.id())).contains(repair);
        var stopped = repairs.save(Repair.create(UUID.randomUUID(), UUID.randomUUID(), failed.id()).cancel());
        assertThat(repairs.findById(stopped.id())).contains(stopped);
    }

    @Test
    void historyIsChronologicalIsolatedAndInsertOnly() {
        UUID osId = UUID.randomUUID();
        UUID diagnosticId = UUID.randomUUID();
        UUID repairId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var early = new ExecutionHistory(UUID.randomUUID(), osId, diagnosticId, repairId,
                "repair.started.v1", "Inicio", Instant.parse("2026-10-10T10:00:00Z"), correlationId);
        var late = new ExecutionHistory(UUID.randomUUID(), osId, null, null,
                "technical.note", "Nota", early.createdAt().plusSeconds(60), null);
        assertThat(history.save(late)).isEqualTo(late);
        assertThat(history.save(early)).isEqualTo(early);
        history.save(ExecutionHistory.create(UUID.randomUUID(), null, null, "note", "Outra OS", null));
        assertThat(history.findByOsIdOrderByCreatedAt(osId)).containsExactly(early, late);
        assertThat(history.findByOsIdOrderByCreatedAt(UUID.randomUUID())).isEmpty();
        var changed = new ExecutionHistory(early.id(), osId, null, null, "changed", "Alterado", early.createdAt(), null);
        assertThatThrownBy(() -> history.save(changed)).isInstanceOf(DuplicateKeyException.class);
        assertThat(history.findByOsIdOrderByCreatedAt(osId)).containsExactly(early, late);
    }

    @Test
    void uniqueOsIndexesRejectDuplicatesAndSagaQueriesAllowMultipleOrders() {
        UUID osId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        diagnostics.save(Diagnostic.create(osId, sagaId));
        assertThatThrownBy(() -> diagnostics.save(Diagnostic.create(osId, UUID.randomUUID())))
                .isInstanceOf(DuplicateKeyException.class);
        diagnostics.save(Diagnostic.create(UUID.randomUUID(), sagaId));
        assertThat(diagnostics.findBySagaId(sagaId)).hasSize(2);
        repairs.save(Repair.create(osId, sagaId, UUID.randomUUID()));
        assertThatThrownBy(() -> repairs.save(Repair.create(osId, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DuplicateKeyException.class);
        repairs.save(Repair.create(UUID.randomUUID(), sagaId, UUID.randomUUID()));
        assertThat(repairs.findBySagaId(sagaId)).hasSize(2);
    }

    @Test
    void indexesActuallyExistWithExpectedKeysAndOptions() {
        for (String collection : List.of("diagnostics", "work_orders")) {
            var indexes = mongo.indexOps(collection).getIndexInfo();
            var queue = indexes.stream().filter(index -> index.getName().equals("queue_status_created"))
                    .findFirst().orElseThrow();
            assertThat(queue.getIndexFields()).extracting(field -> field.getKey())
                    .containsExactly("status", "createdAt", "_id");
            IndexInfo unique = indexes.stream().filter(index -> index.getName().equals("unique_os")).findFirst().orElseThrow();
            assertThat(unique.isUnique()).isTrue();
            assertThat(unique.getIndexFields()).singleElement().satisfies(field -> {
                assertThat(field.getKey()).isEqualTo("osId");
                assertThat(field.getDirection()).isEqualTo(Sort.Direction.ASC);
            });
            assertThat(indexes.stream().filter(index -> index.getName().equals("saga")).findFirst().orElseThrow()
                    .getIndexFields()).singleElement().satisfies(field -> assertThat(field.getKey()).isEqualTo("sagaId"));
        }
        var chronological = mongo.indexOps("execution_history").getIndexInfo().stream()
                .filter(index -> index.getName().equals("history_os_created")).findFirst().orElseThrow();
        assertThat(chronological.getIndexFields()).extracting(field -> field.getKey()).containsExactly("osId", "createdAt");
        assertThat(chronological.getIndexFields()).allSatisfy(field -> assertThat(field.getDirection()).isEqualTo(Sort.Direction.ASC));
    }

    @Test
    void staleVersionsCannotOverwriteEitherAggregate() {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
        var staleDiagnostic = diagnostics.findById(diagnostic.id()).orElseThrow();
        var started = diagnostics.save(diagnostic.start());
        assertThat(started.version()).isEqualTo(1L);
        assertThatThrownBy(() -> diagnostics.save(staleDiagnostic.cancel())).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(diagnostics.findById(started.id())).contains(started);
        var repair = repairs.save(Repair.create(diagnostic.osId(), diagnostic.sagaId(), diagnostic.id()));
        var staleRepair = repairs.findById(repair.id()).orElseThrow();
        var authorized = repairs.save(repair.authorizeAndStart());
        assertThat(authorized.version()).isEqualTo(1L);
        assertThatThrownBy(() -> repairs.save(staleRepair.cancel())).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repairs.findById(repair.id())).contains(authorized);
    }

    @Test
    void replicaSetSupportsAtomicRollbackAcrossCollections() {
        UUID osId = UUID.randomUUID();
        var transaction = new TransactionTemplate(new MongoTransactionManager(databaseFactory));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var diagnostic = diagnostics.save(Diagnostic.create(osId, UUID.randomUUID()));
            history.save(ExecutionHistory.create(osId, diagnostic.id(), null, "note", "Inicio", null));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(diagnostics.findByOsId(osId)).isEmpty();
        assertThat(history.findByOsIdOrderByCreatedAt(osId)).isEmpty();
    }
}
