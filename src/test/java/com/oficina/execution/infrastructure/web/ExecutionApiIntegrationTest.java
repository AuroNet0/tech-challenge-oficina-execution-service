package com.oficina.execution.infrastructure.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.*;
import com.oficina.execution.infrastructure.persistence.adapter.DiagnosticRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.adapter.ExecutionHistoryRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.document.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"spring.security.user.name=technician", "spring.security.user.password=test-pass"})
@AutoConfigureMockMvc
@WithMockUser
class ExecutionApiIntegrationTest {
    @Container
    static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("execution_api_test"));
    }

    @Autowired MockMvc mvc;
    @Autowired DiagnosticRepositoryPort diagnostics;
    @Autowired RepairRepositoryPort repairs;
    @Autowired ExecutionHistoryRepositoryPort history;
    @Autowired MongoTemplate mongo;
    @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping;
    @MockitoSpyBean DiagnosticRepositoryAdapter diagnosticAdapter;
    @MockitoSpyBean ExecutionHistoryRepositoryAdapter historyAdapter;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), DiagnosticDocument.class);
        mongo.remove(new Query(), RepairDocument.class);
        mongo.remove(new Query(), ExecutionHistoryDocument.class);
    }

    private Diagnostic diagnostic() {
        return diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start());
    }

    private DiagnosticItem item() {
        return new DiagnosticItem("item-1", "Troca de pastilhas", DiagnosticItemType.PECA,
                new BigDecimal("2.500"), PriceUnit.UNIDADE, new BigDecimal("150.0000"));
    }

    private Repair repair(boolean authorized) {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().addItem(item()).complete());
        var repair = Repair.create(diagnostic.osId(), diagnostic.sagaId(), diagnostic.id());
        return repairs.save(authorized ? repair.authorizeAndStart() : repair);
    }

    private String itemJson() {
        return """
                {"itemId":"item-1","descricao":"Troca de pastilhas","tipo":"PECA",
                 "quantidade":"2.500","unidadePreco":"UNIDADE",
                 "precoPrevisto":"1234567890123456789012345678901234567890.000100"}
                """;
    }

    @Test
    void queriesExistingAggregatesByIdAndOsId() throws Exception {
        var diagnostic = diagnostic();
        var repair = repair(true);
        for (String url : List.of("/api/v1/diagnosticos/" + diagnostic.id(),
                "/api/v1/diagnosticos?osId=" + diagnostic.osId())) {
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(diagnostic.id().toString()))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.version").value(0));
        }
        for (String url : List.of("/api/v1/reparos/" + repair.id(),
                "/api/v1/reparos?osId=" + repair.osId())) {
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.diagnosticId").value(repair.diagnosticId().toString()));
        }
    }

    @Test
    void addsPreciseItemsCompletesAndPersistsCorrespondingHistory() throws Exception {
        var diagnostic = diagnostic();
        UUID correlationId = UUID.randomUUID();
        String base = "/api/v1/diagnosticos/" + diagnostic.id();
        mvc.perform(post(base + "/itens").contentType(MediaType.APPLICATION_JSON).content(itemJson())
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items[0].quantidade").isString())
                .andExpect(jsonPath("$.items[0].quantidade").value("2.500"))
                .andExpect(jsonPath("$.items[0].precoPrevisto").isString())
                .andExpect(jsonPath("$.items[0].precoPrevisto").value("1234567890123456789012345678901234567890.000100"));
        var persisted = diagnostics.findById(diagnostic.id()).orElseThrow();
        assertThat(persisted.items().getFirst().precoPrevisto())
                .isEqualTo(new BigDecimal("1234567890123456789012345678901234567890.000100"));
        mvc.perform(post(base + "/conclusao").header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty()).andExpect(jsonPath("$.version").value(2));
        assertThat(diagnostics.findById(diagnostic.id()).orElseThrow().status()).isEqualTo(DiagnosticStatus.COMPLETED);
        var entries = history.findByOsIdOrderByCreatedAt(diagnostic.osId());
        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.diagnosticId()).isEqualTo(diagnostic.id());
            assertThat(entry.correlationId()).isEqualTo(correlationId);
        });
        assertThat(entries).extracting(ExecutionHistory::eventType)
                .containsExactlyInAnyOrder("diagnostic.item-added", "diagnostic.completed.v1");
    }

    @Test
    void updatesDiagnosticObservationsAtomically() throws Exception {
        var diagnostic = diagnostic();
        mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"observacoes\":\" Verificar motor \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observacoes").value("Verificar motor"));
        assertThat(diagnostics.findById(diagnostic.id()).orElseThrow().observacoes()).isEqualTo("Verificar motor");
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).singleElement()
                .satisfies(entry -> assertThat(entry.eventType()).isEqualTo("diagnostic.observations-updated"));
    }

    @Test
    void updatesAndCompletesAuthorizedRepairWithHistory() throws Exception {
        var repair = repair(true);
        UUID correlationId = UUID.randomUUID();
        String base = "/api/v1/reparos/" + repair.id();
        mvc.perform(patch(base + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacoes\":\" Reparo efetuado \"}").header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observacoes").value("Reparo efetuado"));
        mvc.perform(post(base + "/conclusao").header("X-Correlation-Id", correlationId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        var persisted = repairs.findById(repair.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(RepairStatus.COMPLETED);
        assertThat(persisted.version()).isEqualTo(2L);
        assertThat(history.findByOsIdOrderByCreatedAt(repair.osId())).hasSize(2)
                .allSatisfy(entry -> {
                    assertThat(entry.repairId()).isEqualTo(repair.id());
                    assertThat(entry.diagnosticId()).isEqualTo(repair.diagnosticId());
                    assertThat(entry.correlationId()).isEqualTo(correlationId);
                });
    }

    @Test
    void rejectsPendingRepairCompletionAndTerminalEdits() throws Exception {
        var pending = repair(false);
        mvc.perform(post("/api/v1/reparos/" + pending.id() + "/conclusao")).andExpect(status().isConflict());
        assertThat(history.findByOsIdOrderByCreatedAt(pending.osId())).isEmpty();
        assertThat(repairs.findById(pending.id()).orElseThrow().startedAt()).isNull();
        for (Repair terminal : List.of(repairs.save(pending.cancel()), repairs.save(repair(true).complete()))) {
            mvc.perform(patch("/api/v1/reparos/" + terminal.id() + "/observacoes")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"observacoes\":\"alterado\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/v1/reparos/" + terminal.id() + "/conclusao")).andExpect(status().isConflict());
        }
    }

    @Test
    void rejectsDuplicateItemsInvalidStatesAndCompletionWithoutItems() throws Exception {
        var diagnostic = diagnostic();
        String base = "/api/v1/diagnosticos/" + diagnostic.id();
        mvc.perform(post(base + "/conclusao")).andExpect(status().isConflict());
        mvc.perform(post(base + "/itens").contentType(MediaType.APPLICATION_JSON).content(itemJson())).andExpect(status().isOk());
        mvc.perform(post(base + "/itens").contentType(MediaType.APPLICATION_JSON).content(itemJson()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(post(base + "/conclusao")).andExpect(status().isOk());
        mvc.perform(post(base + "/itens").contentType(MediaType.APPLICATION_JSON).content(itemJson())).andExpect(status().isConflict());
        mvc.perform(patch(base + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observacoes\":\"alterado\"}")).andExpect(status().isConflict());
        var pending = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
        mvc.perform(post("/api/v1/diagnosticos/" + pending.id() + "/itens")
                .contentType(MediaType.APPLICATION_JSON).content(itemJson())).andExpect(status().isConflict());
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"itemId\":\"a\"}",
            "{\"itemId\":\" \",\"descricao\":\"x\",\"tipo\":\"PECA\",\"quantidade\":\"1\",\"unidadePreco\":\"HORA\",\"precoPrevisto\":\"0\"}",
            "{\"itemId\":\"a\",\"descricao\":\"x\",\"tipo\":\"INVALID\",\"quantidade\":\"1\",\"unidadePreco\":\"HORA\",\"precoPrevisto\":\"0\"}"})
    void rejectsMalformedOrIncompleteItems(String body) throws Exception {
        var diagnostic = diagnostic();
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/itens")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(diagnostics.findById(diagnostic.id()).orElseThrow().items()).isEmpty();
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "NaN", "1e5", ""})
    void rejectsInvalidDecimalQuantities(String quantity) throws Exception {
        var diagnostic = diagnostic();
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/itens")
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson().replace("2.500", quantity)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativePriceMissingObservationsAndInvalidIdentifiers() throws Exception {
        var diagnostic = diagnostic();
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/itens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemJson().replace("1234567890123456789012345678901234567890.000100", "-0.01")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        for (String url : List.of("/api/v1/diagnosticos/not-a-uuid", "/api/v1/reparos/not-a-uuid",
                "/api/v1/diagnosticos", "/api/v1/reparos?osId=invalid", "/api/v1/execucoes/historico?osId=invalid")) {
            mvc.perform(get(url)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/conclusao")
                .header("X-Correlation-Id", "invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForMissingAggregates() throws Exception {
        UUID id = UUID.randomUUID();
        for (String path : List.of("diagnosticos", "reparos")) {
            String base = "/api/v1/" + path;
            mvc.perform(get(base + "/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
            mvc.perform(get(base).param("osId", id.toString())).andExpect(status().isNotFound());
            mvc.perform(post(base + "/" + id + "/conclusao")).andExpect(status().isNotFound());
            mvc.perform(patch(base + "/" + id + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"nota\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/diagnosticos/" + id + "/itens")
                .contentType(MediaType.APPLICATION_JSON).content(itemJson())).andExpect(status().isNotFound());
    }

    @Test
    void returnsChronologicalHistoryWithOptionalReferences() throws Exception {
        UUID osId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var first = new ExecutionHistory(UUID.randomUUID(), osId, UUID.randomUUID(), UUID.randomUUID(),
                "note.first", "Primeiro", Instant.parse("2026-10-19T10:00:00Z"), correlationId);
        var second = new ExecutionHistory(UUID.randomUUID(), osId, null, null,
                "note.second", "Segundo", first.createdAt().plusSeconds(60), null);
        history.save(second);
        history.save(first);
        mvc.perform(get("/api/v1/execucoes/historico").param("osId", osId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("note.first"))
                .andExpect(jsonPath("$[0].description").value("Primeiro"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-19T10:00:00Z"))
                .andExpect(jsonPath("$[0].correlationId").value(correlationId.toString()))
                .andExpect(jsonPath("$[1].eventType").value("note.second"));
        mvc.perform(get("/api/v1/execucoes/historico").param("osId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    @WithAnonymousUser
    void requiresAuthenticationAndAcceptsTemporaryHttpBasic() throws Exception {
        UUID id = UUID.randomUUID();
        for (String path : List.of("diagnosticos", "reparos")) {
            mvc.perform(get("/api/v1/" + path + "/" + id)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/" + path).param("osId", id.toString())).andExpect(status().isUnauthorized());
            mvc.perform(patch("/api/v1/" + path + "/" + id + "/observacoes")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"observacoes\":\"nota\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/" + path + "/" + id + "/conclusao")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/diagnosticos/" + id + "/itens").contentType(MediaType.APPLICATION_JSON).content(itemJson()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/execucoes/historico").param("osId", id.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/execucoes/historico").param("osId", id.toString()).with(httpBasic("technician", "test-pass")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/execucoes/historico").param("osId", id.toString()).with(httpBasic("technician", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesOnlyRequestedBusinessRoutesAndNoAuthorizationOrGenericStatus() throws Exception {
        var paths = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).filter(path -> path.startsWith("/api/v1/"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(paths).isEqualTo(Set.of("/api/v1/diagnosticos", "/api/v1/diagnosticos/{id}",
                "/api/v1/diagnosticos/{id}/itens", "/api/v1/diagnosticos/{id}/observacoes", "/api/v1/diagnosticos/{id}/conclusao",
                "/api/v1/reparos", "/api/v1/reparos/{id}", "/api/v1/reparos/{id}/observacoes", "/api/v1/reparos/{id}/conclusao",
                "/api/v1/execucoes/historico"));
        for (String path : List.of("diagnosticos", "reparos")) {
            mvc.perform(post("/api/v1/" + path)).andExpect(status().isMethodNotAllowed());
            mvc.perform(patch("/api/v1/" + path + "/" + UUID.randomUUID() + "/status")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}")).andExpect(status().isNotFound());
        }
        for (String operation : List.of("inicio", "autorizacao", "start", "authorizeAndStart")) {
            mvc.perform(post("/api/v1/reparos/" + UUID.randomUUID() + "/" + operation)).andExpect(status().isNotFound());
        }
    }

    @Test
    void rollsBackBothCollectionsWhenHistoryFailsAfterInsertion() throws Exception {
        var diagnostic = diagnostic();
        var repair = repair(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("internal MongoDB details must not escape");
        }).when(historyAdapter).save(any());
        for (String base : List.of("/api/v1/diagnosticos/" + diagnostic.id(), "/api/v1/reparos/" + repair.id())) {
            mvc.perform(patch(base + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"observacoes\":\"nao persistir\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Erro interno ao processar a solicitacao."))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("MongoDB"))));
        }
        assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
        assertThat(repairs.findById(repair.id())).contains(repair);
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).isEmpty();
        assertThat(history.findByOsIdOrderByCreatedAt(repair.osId())).isEmpty();
    }

    @Test
    void mapsVersionAndDuplicateConflictsWithoutLeakingPersistenceDetails() throws Exception {
        var diagnostic = diagnostic();
        for (RuntimeException conflict : List.of(new OptimisticLockingFailureException("secret version"),
                new DuplicateKeyException("secret unique index"))) {
            doThrow(conflict).when(diagnosticAdapter).save(any());
            mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"observacoes\":\"nota\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        }
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).isEmpty();
    }

    @Test
    void concurrentHttpUpdatesCommitOnlyOneStateAndOneHistory() throws Exception {
        var diagnostic = diagnostic();
        var barrier = new CyclicBarrier(2);
        var reads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) barrier.await(20, TimeUnit.SECONDS);
            return result;
        }).when(diagnosticAdapter).findById(diagnostic.id());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                    .with(httpBasic("technician", "test-pass")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"primeiro\"}")).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                    .with(httpBasic("technician", "test-pass")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"segundo\"}")).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var persisted = diagnostics.findById(diagnostic.id()).orElseThrow();
        assertThat(persisted.version()).isEqualTo(1L);
        assertThat(persisted.observacoes()).isIn("primeiro", "segundo");
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).hasSize(1);
    }
}
