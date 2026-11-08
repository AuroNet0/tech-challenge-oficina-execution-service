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
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.*;
import com.oficina.execution.infrastructure.persistence.adapter.DiagnosticRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.adapter.ExecutionHistoryRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.adapter.RepairRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.adapter.OutboxRepositoryAdapter;
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
import com.oficina.execution.security.JwksFixture;
import com.oficina.execution.security.JwtTestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(JwtTestConfiguration.class)
class ExecutionApiIntegrationTest {
    @Container
    static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGODB.getReplicaSetUrl("execution_api_test"));
    }

    @Autowired MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired JwksFixture jwks;
    @MockitoBean TechnicalAuthorizationPort authorization;
    @Autowired DiagnosticRepositoryPort diagnostics;
    @Autowired RepairRepositoryPort repairs;
    @Autowired ExecutionHistoryRepositoryPort history;
    @Autowired MongoTemplate mongo;
    @Autowired RequestMappingHandlerMapping requestMappingHandlerMapping;
    @MockitoSpyBean DiagnosticRepositoryAdapter diagnosticAdapter;
    @MockitoSpyBean ExecutionHistoryRepositoryAdapter historyAdapter;
    @MockitoSpyBean RepairRepositoryAdapter repairAdapter;
    @MockitoSpyBean OutboxRepositoryAdapter outboxAdapter;

    @BeforeEach
    void clean() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + jwks.token("MECANICO"))).build();
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(), request.correlationId(), true);
        }).when(authorization).decide(any());
        mongo.remove(new Query(), DiagnosticDocument.class);
        mongo.remove(new Query(), RepairDocument.class);
        mongo.remove(new Query(), ExecutionHistoryDocument.class);
        mongo.remove(new Query(), OutboxEventDocument.class);
        mongo.remove(new Query(), InboxMessageDocument.class);
    }

    private Diagnostic diagnostic() {
        return diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start());
    }

    @Test
    void queueOrdersBothTypesFiltersUnauthorizedAndExcludesNonPending() throws Exception {
        var oldest = Diagnostic.create(UUID.randomUUID(), UUID.randomUUID());
        oldest = diagnostics.save(new Diagnostic(oldest.id(), oldest.osId(), oldest.sagaId(), oldest.status(),
                null, oldest.items(), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), null, null));
        var pendingRepair = repair(false);
        var hidden = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
        diagnostic();
        repair(true);
        diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().fail("falha"));
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(),
                    request.correlationId(), !request.osId().equals(hidden.osId()));
        }).when(authorization).decide(any());
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(oldest.id().toString()))
                .andExpect(jsonPath("$[0].type").value("DIAGNOSTIC"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].id").value(pendingRepair.id().toString()))
                .andExpect(jsonPath("$[1].type").value("REPAIR"))
                .andExpect(jsonPath("$[0].observacoes").doesNotExist())
                .andExpect(jsonPath("$[0].items").doesNotExist());
        mvc.perform(get("/api/v1/diagnosticos/" + hidden.id())).andExpect(status().isForbidden());
        doThrow(new IllegalStateException("upstream unavailable")).when(authorization).decide(any());
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"diagnosticos", "reparos"})
    void authorizedStartIsPersistedWithHistoryOutboxAndCannotRepeat(String path) throws Exception {
        UUID id;
        UUID osId;
        if (path.equals("diagnosticos")) {
            var pending = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
            id = pending.id();
            osId = pending.osId();
        } else {
            var pending = repair(false);
            id = pending.id();
            osId = pending.osId();
        }
        UUID correlation = UUID.randomUUID();
        String base = "/api/v1/" + path + "/" + id;
        mvc.perform(post(base + "/inicio").header("X-Correlation-Id", correlation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post(base + "/inicio")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(history.findByOsIdOrderByCreatedAt(osId)).singleElement().satisfies(entry ->
                assertThat(entry.correlationId()).isEqualTo(correlation));
        assertThat(mongo.findAll(OutboxEventDocument.class)).singleElement().satisfies(event -> {
            assertThat(event.correlationId()).isEqualTo(correlation.toString());
            assertThat(event.eventType()).endsWith(".started.v1");
        });
        verify(authorization, atLeastOnce()).decide(argThat(request ->
                request.usuarioId().equals(JwksFixture.USER) && request.osId().equals(osId)
                        && request.capability().name().endsWith("_WRITE")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"diagnosticos", "reparos"})
    void concurrentStartsCommitExactlyOneTransitionHistoryAndEvent(String path) throws Exception {
        UUID id = path.equals("diagnosticos")
                ? diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID())).id() : repair(false).id();
        var barrier = new CyclicBarrier(2);
        if (path.equals("diagnosticos")) {
            doAnswer(invocation -> {
                barrier.await(20, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(diagnosticAdapter).save(any());
        } else {
            doAnswer(invocation -> {
                barrier.await(20, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(repairAdapter).save(any());
        }
        var otherClaims = jwks.claims("MECANICO");
        otherClaims.put("sub", UUID.randomUUID().toString());
        String otherToken = jwks.sign(otherClaims, jwks.key, com.nimbusds.jose.JWSAlgorithm.RS256, jwks.key.getKeyID());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(post("/api/v1/" + path + "/" + id + "/inicio")
                    .header("Authorization", "Bearer " + jwks.token("MECANICO"))).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> mvc.perform(post("/api/v1/" + path + "/" + id + "/inicio")
                    .header("Authorization", "Bearer " + otherToken)).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(1);
    }

    @Test
    void startFailuresRollbackBothAggregatesHistoryAndOutbox() throws Exception {
        var pendingDiagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
        var pendingRepair = repair(false);
        for (String failure : List.of("history", "outbox")) {
            if (failure.equals("history")) {
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("failure after insert");
                }).when(historyAdapter).save(any());
            } else {
                doCallRealMethod().when(historyAdapter).save(any());
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("failure after insert");
                }).when(outboxAdapter).save(any());
            }
            mvc.perform(post("/api/v1/diagnosticos/" + pendingDiagnostic.id() + "/inicio"))
                    .andExpect(status().isInternalServerError());
            mvc.perform(post("/api/v1/reparos/" + pendingRepair.id() + "/inicio"))
                    .andExpect(status().isInternalServerError());
            assertThat(diagnostics.findById(pendingDiagnostic.id())).contains(pendingDiagnostic);
            assertThat(repairs.findById(pendingRepair.id())).contains(pendingRepair);
            assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isZero();
            assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
        }
    }

    @Test
    void queueAndStartRequireAuthenticatedMechanicAndCurrentCapability() throws Exception {
        var pending = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()));
        var pendingRepair = repair(false);
        var anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        anonymous.perform(get("/api/v1/execucoes/fila")).andExpect(status().isUnauthorized());
        for (String base : List.of("/api/v1/diagnosticos/" + pending.id(), "/api/v1/reparos/" + pendingRepair.id())) {
            anonymous.perform(post(base + "/inicio")).andExpect(status().isUnauthorized());
            mvc.perform(post(base + "/inicio").header("Authorization", "Bearer " + jwks.token("GERENTE")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/execucoes/fila").header("Authorization", "Bearer " + jwks.token("CLIENTE")))
                .andExpect(status().isForbidden());
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(),
                    request.correlationId(), request.capability().name().endsWith("_READ"));
        }).when(authorization).decide(any());
        mvc.perform(post("/api/v1/diagnosticos/" + pending.id() + "/inicio")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reparos/" + pendingRepair.id() + "/inicio")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/diagnosticos/" + UUID.randomUUID() + "/inicio")).andExpect(status().isNotFound());
        assertThat(diagnostics.findById(pending.id())).contains(pending);
        assertThat(repairs.findById(pendingRepair.id())).contains(pendingRepair);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
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
    void requiresBearerAuthenticationAndRejectsBasic() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
                .andExpect(status().isUnauthorized());
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
                "/api/v1/execucoes/historico", "/api/v1/execucoes/fila",
                "/api/v1/diagnosticos/{id}/inicio", "/api/v1/reparos/{id}/inicio"));
        for (String path : List.of("diagnosticos", "reparos")) {
            mvc.perform(post("/api/v1/" + path)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/" + path + "/" + UUID.randomUUID() + "/status")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}")).andExpect(status().isForbidden());
        }
        for (String operation : List.of("autorizacao", "start", "authorizeAndStart")) {
            mvc.perform(post("/api/v1/reparos/" + UUID.randomUUID() + "/" + operation)).andExpect(status().isForbidden());
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
        doAnswer(invocation -> {
            barrier.await(20, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(diagnosticAdapter).save(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                    .header("Authorization", "Bearer " + jwks.token("MECANICO")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"primeiro\"}")).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes")
                    .header("Authorization", "Bearer " + jwks.token("MECANICO")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"segundo\"}")).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var persisted = diagnostics.findById(diagnostic.id()).orElseThrow();
        assertThat(persisted.version()).isEqualTo(1L);
        assertThat(persisted.observacoes()).isIn("primeiro", "segundo");
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).hasSize(1);
    }

    @Test
    void rejectsInsufficientRolesAndEmptyRolesBeforeLoadingResources() throws Exception {
        var diagnostic = diagnostic();
        var repair = repair(true);
        clearInvocations(authorization);
        for (String[] roles : List.of(new String[]{"CLIENTE"}, new String[]{"ATENDENTE"}, new String[]{"GERENTE"}, new String[]{})) {
            String bearer = "Bearer " + jwks.token(roles);
            mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id()).header("Authorization", bearer)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/itens").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(itemJson())).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/reparos/" + repair.id() + "/conclusao").header("Authorization", bearer)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/execucoes/historico").param("osId", diagnostic.osId().toString())
                    .header("Authorization", bearer)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(authorization);
        assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
        assertThat(repairs.findById(repair.id())).contains(repair);
    }

    @Test
    void deniesUnassignedRevokedWrongOsAndOutageWithoutAnyMutation() throws Exception {
        var diagnostic = diagnostic();
        var repair = repair(true);
        long initialHistory = mongo.count(new Query(), ExecutionHistoryDocument.class);
        long initialOutbox = mongo.count(new Query(), OutboxEventDocument.class);
        for (int scenario = 0; scenario < 5; scenario++) {
            int selected = scenario;
            doAnswer(invocation -> {
                TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
                if (selected == 4) throw new IllegalStateException("sensitive upstream details");
                return new TechnicalAuthorizationPort.Decision(request.usuarioId(), selected == 2 ? UUID.randomUUID() : request.osId(),
                        selected == 3 ? (request.capability() == TechnicalAuthorizationPort.Capability.DIAGNOSTIC_READ
                                ? TechnicalAuthorizationPort.Capability.DIAGNOSTIC_WRITE : TechnicalAuthorizationPort.Capability.DIAGNOSTIC_READ) : request.capability(),
                        request.correlationId(), selected >= 2);
            }).when(authorization).decide(any());
            int expected = scenario == 4 ? 503 : 403;
            mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/itens").contentType(MediaType.APPLICATION_JSON)
                    .content(itemJson())).andExpect(status().is(expected));
            mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"nao persistir\"}")).andExpect(status().is(expected));
            mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/conclusao")).andExpect(status().is(expected));
            mvc.perform(patch("/api/v1/reparos/" + repair.id() + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"observacoes\":\"nao persistir\"}")).andExpect(status().is(expected));
            mvc.perform(post("/api/v1/reparos/" + repair.id() + "/conclusao")).andExpect(status().is(expected));
            mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id())).andExpect(status().is(expected));
            mvc.perform(get("/api/v1/reparos/" + repair.id())).andExpect(status().is(expected));
            mvc.perform(get("/api/v1/diagnosticos").param("osId", diagnostic.osId().toString()))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.items").doesNotExist());
            mvc.perform(get("/api/v1/reparos").param("osId", repair.osId().toString()))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.diagnosticId").doesNotExist());
            mvc.perform(get("/api/v1/execucoes/historico").param("osId", diagnostic.osId().toString()))
                    .andExpect(status().is(expected));
            assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
            assertThat(repairs.findById(repair.id())).contains(repair);
            assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isEqualTo(initialHistory);
            assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(initialOutbox);
        }
    }

    @Test
    void currentDecisionRevokesNextRequestAndDoesNotReuseReadPermission() throws Exception {
        var diagnostic = diagnostic();
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            assertThat(request.osId()).isEqualTo(diagnostic.osId());
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(),
                    request.correlationId(), request.capability() == TechnicalAuthorizationPort.Capability.DIAGNOSTIC_READ);
        }).when(authorization).decide(any());
        mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id()).param("osId", UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/diagnosticos/" + diagnostic.id() + "/observacoes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"observacoes\":\"nao persistir\"}")).andExpect(status().isForbidden());
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(), request.correlationId(), false);
        }).when(authorization).decide(any());
        mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id())).andExpect(status().isForbidden());
        assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
    }

    @Test
    void realBearerRejectsInvalidSignatureClaimsAndExpirationBeforeAuthorization() throws Exception {
        var diagnostic = diagnostic();
        var otherKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("other").generate();
        clearInvocations(authorization);
        var valid = jwks.claims("MECANICO");
        var expired = jwks.claims("MECANICO"); expired.put("exp", Instant.now().getEpochSecond() - 1);
        var issuer = jwks.claims("MECANICO"); issuer.put("iss", "https://other.test");
        var audience = jwks.claims("MECANICO"); audience.put("aud", List.of("other"));
        var subject = jwks.claims("MECANICO"); subject.put("sub", "invalid");
        var roles = jwks.claims("MECANICO"); roles.put("roles", "MECANICO");
        for (var claims : List.of(expired, issuer, audience, subject, roles)) {
            mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id()).header("Authorization", "Bearer "
                    + jwks.sign(claims, jwks.key, com.nimbusds.jose.JWSAlgorithm.RS256, jwks.key.getKeyID())))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/diagnosticos/" + diagnostic.id()).header("Authorization", "Bearer "
                + jwks.sign(valid, otherKey, com.nimbusds.jose.JWSAlgorithm.RS256, jwks.key.getKeyID())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authorization);
        assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
    }
}
