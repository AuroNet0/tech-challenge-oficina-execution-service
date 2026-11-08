package com.oficina.execution.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.oficina.execution.application.message.*;
import com.oficina.execution.application.port.out.*;
import com.oficina.execution.application.usecase.*;
import com.oficina.execution.domain.enums.*;
import com.oficina.execution.domain.model.*;
import com.oficina.execution.infrastructure.persistence.adapter.OutboxRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.adapter.ExecutionHistoryRepositoryAdapter;
import com.oficina.execution.infrastructure.persistence.document.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import com.oficina.execution.security.JwksFixture;
import com.oficina.execution.security.JwtTestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Testcontainers
@SpringBootTest(properties = {"execution.messaging.enabled=true", "execution.outbox.enabled=false",
        "execution.messaging.retry-delay=100ms", "execution.messaging.max-deliveries=3"})
@AutoConfigureMockMvc
@Import(JwtTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessagingIntegrationTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("execution_messaging_test"));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    private static final String OBSERVER = "test.os.saga.events";
    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired ObjectMapper mapper;
    @Autowired MessageCodec codec;
    @Autowired CommandHandler handler;
    @Autowired DiagnosticRepositoryPort diagnostics;
    @Autowired RepairRepositoryPort repairs;
    @Autowired InboxRepositoryPort inbox;
    @Autowired OutboxRepositoryPort outbox;
    @Autowired ExecutionHistoryRepositoryPort history;
    @Autowired EventRecorder events;
    @Autowired OutboxPublisher publisher;
    @Autowired RabbitMessageBrokerAdapter broker;
    @Autowired DiagnosticService diagnosticService;
    @Autowired RepairService repairService;
    @Autowired MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired JwksFixture jwks;
    @MockitoBean TechnicalAuthorizationPort authorization;
    @MockitoSpyBean OutboxRepositoryAdapter outboxAdapter;
    @MockitoSpyBean ExecutionHistoryRepositoryAdapter historyAdapter;

    private Binding observerBinding() {
        return BindingBuilder.bind(new org.springframework.amqp.core.Queue(OBSERVER))
                .to(new TopicExchange(RabbitMessagingConfig.EVENTS)).with("#.v1");
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + jwks.token("MECANICO"))).build();
        doAnswer(invocation -> {
            TechnicalAuthorizationPort.Request request = invocation.getArgument(0);
            return new TechnicalAuthorizationPort.Decision(request.usuarioId(), request.osId(), request.capability(), request.correlationId(), true);
        }).when(authorization).decide(any());
        for (Class<?> document : List.of(DiagnosticDocument.class, RepairDocument.class,
                ExecutionHistoryDocument.class, InboxMessageDocument.class, OutboxEventDocument.class))
            mongo.remove(new Query(), document);
        admin.declareQueue(new org.springframework.amqp.core.Queue(OBSERVER, true));
        admin.declareBinding(observerBinding());
        admin.declareQueue(new org.springframework.amqp.core.Queue("execution.diagnostic.start", true));
        for (String queue : List.of(OBSERVER, RabbitMessagingConfig.DLQ, "execution.diagnostic.start",
                "execution.repair.start", "execution.repair.cancel", "execution.diagnostic.start.retry",
                "execution.repair.start.retry", "execution.repair.cancel.retry"))
            admin.purgeQueue(queue, false);
    }

    private CommandEnvelope command(String type, UUID osId, UUID sagaId) {
        CommandPayload payload = switch (type) {
            case "diagnostic.start.v1" -> new CommandPayload.DiagnosticStart(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "nota");
            case "repair.start.v1" -> new CommandPayload.RepairStart();
            default -> new CommandPayload.RepairCancel("Solicitacao compensatoria");
        };
        return new CommandEnvelope(UUID.randomUUID(), type, Instant.now(), UUID.randomUUID(), sagaId, osId, "os-service", payload);
    }

    private String body(CommandEnvelope command) {
        return codec.serialize(new EventEnvelope<>(command.eventId(), command.eventType(), command.occurredAt(),
                command.correlationId(), command.sagaId(), command.osId(), command.source(), command.payload()));
    }

    private InboxStatus handle(CommandEnvelope command) {
        return handler.handle(command, body(command));
    }

    private void handleAndStart(CommandEnvelope command) {
        handle(command);
        diagnosticService.start(diagnostics.findByOsId(command.osId()).orElseThrow().id(), null);
    }

    private void send(CommandEnvelope command) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(command.eventId().toString());
        rabbit.send(RabbitMessagingConfig.COMMANDS, command.eventType(),
                new Message(body(command).getBytes(StandardCharsets.UTF_8), properties));
    }

    private void processed(CommandEnvelope command) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inbox.findByEventId(command.eventId()).map(InboxMessage::status)).contains(InboxStatus.PROCESSED));
    }

    private Diagnostic completedDiagnostic(UUID osId, UUID sagaId) {
        return diagnostics.save(Diagnostic.create(osId, sagaId).start().addItem(item()).complete());
    }

    private DiagnosticItem item() {
        return new DiagnosticItem("item-1", "Servico", DiagnosticItemType.SERVICO, new BigDecimal("1.250"),
                PriceUnit.HORA, new BigDecimal("1234567890123456789012345678901234567890.00100"));
    }

    private OutboxEventDocument event(UUID osId) {
        return mongo.findOne(Query.query(Criteria.where("osId").is(osId.toString())), OutboxEventDocument.class);
    }

    private JsonNode receiveEvent() {
        Message received = rabbit.receive(OBSERVER, 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
        return mapper.readTree(received.getBody());
    }

    @Test
    void receivesDiagnosticPendingThenPublishesStartedOnlyOnOperationalStart() {
        doThrow(new IllegalStateException("HTTP authorization unavailable")).when(authorization).decide(any());
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        send(command);
        processed(command);
        var diagnostic = diagnostics.findByOsId(command.osId()).orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(DiagnosticStatus.PENDING);
        assertThat(history.findByOsIdOrderByCreatedAt(command.osId())).hasSize(1);
        assertThat(event(command.osId())).isNull();
        diagnosticService.start(diagnostic.id(), null);
        assertThat(event(command.osId()).status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(publisher.publishOne()).isTrue();
        JsonNode envelope = receiveEvent();
        assertThat(envelope.get("eventType").asString()).isEqualTo("diagnostic.started.v1");
        assertThat(envelope.get("source").asString()).isEqualTo("execution-service");
        assertThat(envelope.get("sagaId").asString()).isEqualTo(command.sagaId().toString());
        assertThat(envelope.get("osId").asString()).isEqualTo(command.osId().toString());
        assertThat(envelope.get("correlationId").asString()).isEqualTo(command.correlationId().toString());
        assertThat(envelope.get("eventId").asString()).isNotEqualTo(command.eventId().toString());
        assertThat(envelope.get("payload").isObject()).isTrue();
        assertThat(event(command.osId()).status()).isEqualTo(OutboxStatus.PUBLISHED);
        send(command);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(mongo.count(new Query(), DiagnosticDocument.class)).isEqualTo(1);
            assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(1);
            assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isEqualTo(2);
        });
        verifyNoInteractions(authorization);
    }

    @Test
    void repeatedCommandWithNewIdForSameOsDoesNotEmitAnotherStart() {
        UUID osId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        handle(command("diagnostic.start.v1", osId, sagaId));
        handle(command("diagnostic.start.v1", osId, sagaId));
        assertThat(mongo.count(new Query(), InboxMessageDocument.class)).isEqualTo(2);
        assertThat(mongo.count(new Query(), DiagnosticDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
    }

    @Test
    void queuesAuthorizedRepairUsingCompletedDiagnosticAndDeduplicatesCommand() {
        doThrow(new IllegalStateException("HTTP authorization unavailable")).when(authorization).decide(any());
        var diagnostic = completedDiagnostic(UUID.randomUUID(), UUID.randomUUID());
        var command = command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId());
        send(command);
        processed(command);
        var repair = repairs.findByOsId(command.osId()).orElseThrow();
        assertThat(repair.status()).isEqualTo(RepairStatus.PENDING);
        assertThat(repair.diagnosticId()).isEqualTo(diagnostic.id());
        assertThat(history.findByOsIdOrderByCreatedAt(command.osId())).hasSize(1);
        handle(command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId()));
        assertThat(event(command.osId())).isNull();
        repairService.start(repair.id(), null);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(1);
        publisher.publishOne();
        assertThat(receiveEvent().get("eventType").asString()).isEqualTo("repair.started.v1");
        verifyNoInteractions(authorization);
    }

    @Test
    void mismatchedSagaIsRejectedAndDeadLetteredWithoutWrongSagaEvent() {
        var diagnostic = completedDiagnostic(UUID.randomUUID(), UUID.randomUUID());
        var command = command("repair.start.v1", diagnostic.osId(), UUID.randomUUID());
        send(command);
        Message dead = rabbit.receive(RabbitMessagingConfig.DLQ, 10000);
        assertThat(dead).isNotNull();
        assertThat(inbox.findByEventId(command.eventId()).orElseThrow().status()).isEqualTo(InboxStatus.REJECTED);
        assertThat(repairs.findByOsId(command.osId())).isEmpty();
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
    }

    @Test
    void queuedRepairCanBeCancelledBeforeStartAndRedeliveryCannotReopenIt() throws Exception {
        var diagnostic = completedDiagnostic(UUID.randomUUID(), UUID.randomUUID());
        var start = command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId());
        send(start);
        processed(start);
        var repair = repairs.findByOsId(diagnostic.osId()).orElseThrow();
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(repair.id().toString()));
        var cancel = command("repair.cancel.v1", diagnostic.osId(), diagnostic.sagaId());
        send(cancel);
        processed(cancel);
        assertThat(repairs.findById(repair.id()).orElseThrow().status()).isEqualTo(RepairStatus.CANCELLED);
        handle(start);
        handle(cancel);
        handle(command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId()));
        mvc.perform(post("/api/v1/reparos/" + repair.id() + "/inicio")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).extracting(ExecutionHistory::eventType)
                .containsExactly("repair.queued", "repair.cancelled.v1");
        assertThat(mongo.findAll(OutboxEventDocument.class)).singleElement().satisfies(event ->
                assertThat(event.eventType()).isEqualTo("repair.cancelled.v1"));
    }

    @ParameterizedTest
    @EnumSource(RepairStatus.class)
    void cancellationPolicyPublishesTruthfulCompensationResult(RepairStatus status) {
        var diagnostic = completedDiagnostic(UUID.randomUUID(), UUID.randomUUID());
        var pending = Repair.create(diagnostic.osId(), diagnostic.sagaId(), diagnostic.id());
        var repair = repairs.save(switch (status) {
            case PENDING -> pending;
            case IN_PROGRESS -> pending.authorizeAndStart();
            case FAILED -> pending.authorizeAndStart().fail("falha");
            case COMPLETED -> pending.authorizeAndStart().complete();
            case CANCELLED -> pending.cancel();
        });
        var command = command("repair.cancel.v1", repair.osId(), repair.sagaId());
        send(command);
        processed(command);
        publisher.publishOne();
        var envelope = receiveEvent();
        boolean safe = status == RepairStatus.PENDING || status == RepairStatus.CANCELLED;
        assertThat(envelope.get("eventType").asString())
                .isEqualTo(safe ? "repair.cancelled.v1" : "repair.cancellation-failed.v1");
        if (!safe) assertThat(envelope.get("payload").get("reason").asString()).isNotBlank();
        assertThat(repairs.findById(repair.id()).orElseThrow().status()).isEqualTo(safe ? RepairStatus.CANCELLED : status);
    }

    @Test
    void missingOrIncompleteDiagnosticCannotAuthorizeRepairAndProducesFailure() {
        UUID osId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        var missing = command("repair.start.v1", osId, sagaId);
        assertThat(handle(missing)).isEqualTo(InboxStatus.REJECTED);
        assertThat(event(osId).eventType()).isEqualTo("repair.failed.v1");
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start());
        assertThat(handle(command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId()))).isEqualTo(InboxStatus.REJECTED);
        assertThat(repairs.findByOsId(diagnostic.osId())).isEmpty();
        assertThat(event(diagnostic.osId()).payload()).contains("\"reason\"");
    }

    @Test
    void invalidJsonGoesDirectlyToDurableDlq() {
        rabbit.send(RabbitMessagingConfig.COMMANDS, "diagnostic.start.v1", new Message("{".getBytes(StandardCharsets.UTF_8)));
        Message dead = rabbit.receive(RabbitMessagingConfig.DLQ, 10000);
        assertThat(dead).isNotNull();
        assertThat((Object) dead.getMessageProperties().getHeader("execution-attempt")).isEqualTo(1);
        assertThat(mongo.count(new Query(), InboxMessageDocument.class)).isZero();
    }

    @Test
    void transientPersistenceFailureRetriesThenProcessesExactlyOnce() {
        doThrow(new DataAccessResourceFailureException("temporary")).doCallRealMethod().when(historyAdapter).save(any());
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        send(command);
        processed(command);
        assertThat(mongo.count(new Query(), DiagnosticDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), InboxMessageDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
        assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isEqualTo(1);
    }

    @Test
    void exhaustedTransientCommandRetriesReachDlqWithoutPartialState() {
        doThrow(new DataAccessResourceFailureException("unavailable")).when(historyAdapter).save(any());
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        send(command);
        Message dead = rabbit.receive(RabbitMessagingConfig.DLQ, 15000);
        assertThat(dead).isNotNull();
        assertThat((Object) dead.getMessageProperties().getHeader("execution-attempt")).isEqualTo(3);
        assertThat(mongo.count(new Query(), DiagnosticDocument.class)).isZero();
        assertThat(mongo.count(new Query(), InboxMessageDocument.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
    }

    @Test
    void historyInsertFailureRollsBackDiagnosticAndRepairCommandsAcrossAllCollections() {
        var diagnostic = completedDiagnostic(UUID.randomUUID(), UUID.randomUUID());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("after insert");
        }).when(historyAdapter).save(any());
        var startDiagnostic = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        var startRepair = command("repair.start.v1", diagnostic.osId(), diagnostic.sagaId());
        for (var command : List.of(startDiagnostic, startRepair)) {
            assertThatThrownBy(() -> handle(command)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(inbox.findByEventId(command.eventId())).isEmpty();
            assertThat(history.findByOsIdOrderByCreatedAt(command.osId())).isEmpty();
        }
        assertThat(diagnostics.findByOsId(startDiagnostic.osId())).isEmpty();
        assertThat(repairs.findByOsId(diagnostic.osId())).isEmpty();
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
    }

    @Test
    void concurrentConsumersUseUniqueInboxAndRedeliveryDoesNotDuplicate() throws Exception {
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> task = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { return handle(command) == InboxStatus.PROCESSED; }
                catch (RuntimeException conflict) { return false; }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).contains(true);
        }
        assertThat(handle(command)).isEqualTo(InboxStatus.PROCESSED);
        assertThat(mongo.count(new Query(), DiagnosticDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), InboxMessageDocument.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
        assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isEqualTo(1);
    }

    @Test
    void concurrentPublishersOnlyAcquireOneLiveLease() throws Exception {
        handleAndStart(command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(publisher::publishOne);
            var second = executor.submit(publisher::publishOne);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        receiveEvent();
        assertThat(rabbit.receive(OBSERVER, 200)).isNull();
    }

    @Test
    void expiredLeaseIsRecoveredAndOldWorkerCannotUpdateOrPublishSuccess() {
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        handleAndStart(command);
        Instant now = Instant.now();
        var old = outbox.acquire(now, Duration.ofMillis(50), 5).orElseThrow();
        Instant later = now.plusSeconds(1);
        assertThat(outbox.markPublished(old.eventId(), old.leaseToken(), later)).isFalse();
        var recovered = outbox.acquire(later, Duration.ofMinutes(1), 5).orElseThrow();
        assertThat(recovered.eventId()).isEqualTo(old.eventId());
        assertThat(recovered.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(outbox.markPublished(old.eventId(), old.leaseToken(), later)).isFalse();
        assertThat(outbox.markAttemptFailed(old, later, 5, Duration.ofSeconds(1), "old worker")).isFalse();
        broker.publish(recovered);
        assertThat(outbox.markPublished(recovered.eventId(), recovered.leaseToken(), later)).isTrue();
        receiveEvent();
    }

    @Test
    void abandonedLastAttemptBecomesFailedAndBlocksOnlyItsOwnOs() {
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        handleAndStart(command);
        Instant now = Instant.now();
        outbox.acquire(now, Duration.ofMillis(1), 1).orElseThrow();
        assertThat(outbox.acquire(now.plusSeconds(1), Duration.ofSeconds(30), 1)).isEmpty();
        assertThat(event(command.osId()).status()).isEqualTo(OutboxStatus.FAILED);
        events.record(UUID.randomUUID(), "Diagnostic", command.osId(), command.sagaId(), command.correlationId(),
                "diagnostic.completed.v1", new EventPayload.Empty());
        assertThat(outbox.acquire(now.plusSeconds(2), Duration.ofSeconds(30), 5)).isEmpty();
        var other = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        handleAndStart(other);
        assertThat(outbox.acquire(Instant.now(), Duration.ofSeconds(30), 5).orElseThrow().osId()).isEqualTo(other.osId());
    }

    @Test
    void failedBrokerConnectionRetriesWithSameEventIdAndEventuallyPublishes() {
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        handleAndStart(command);
        var originalId = event(command.osId()).eventId();
        var connection = new CachingConnectionFactory("127.0.0.1", 1);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setConnectionTimeout(500);
        var template = new RabbitTemplate(connection);
        template.setMandatory(true);
        var unavailable = new RabbitMessageBrokerAdapter(template,
                new MessagingProperties(Duration.ofMillis(500), Duration.ofMillis(100), 3));
        var properties = new OutboxProperties(false, 1, Duration.ofMinutes(1), 3, Duration.ofMillis(1), Duration.ofSeconds(1));
        try {
            new OutboxPublisher(outbox, unavailable, properties).publishOne();
        } finally {
            connection.destroy();
        }
        assertThat(event(command.osId()).status()).isEqualTo(OutboxStatus.PENDING);
        await().atMost(Duration.ofSeconds(5)).until(() -> new OutboxPublisher(outbox, broker, properties).publishOne());
        var received = receiveEvent();
        assertThat(received.get("eventId").asString()).isEqualTo(originalId);
        assertThat(event(command.osId()).status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event(command.osId()).attempts()).isEqualTo(2);
    }

    @Test
    void mandatoryReturnDoesNotMarkUnroutableEventPublishedAndExhaustionFails() {
        handleAndStart(command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID()));
        admin.removeBinding(observerBinding());
        var properties = new OutboxProperties(false, 1, Duration.ofMinutes(1), 1, Duration.ofMillis(1), Duration.ofMillis(1));
        try {
            assertThat(new OutboxPublisher(outbox, broker, properties).publishOne()).isTrue();
            assertThat(mongo.findAll(OutboxEventDocument.class)).singleElement()
                    .satisfies(event -> assertThat(event.status()).isEqualTo(OutboxStatus.FAILED));
        } finally {
            admin.declareBinding(observerBinding());
        }
    }

    @Test
    void restCompletionsPersistOutboxAndPublishExactDecimalAndRepairContractsInOrder() throws Exception {
        var command = command("diagnostic.start.v1", UUID.randomUUID(), UUID.randomUUID());
        handle(command);
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostics.findByOsId(command.osId()).orElseThrow().id() + "/inicio"))
                .andExpect(status().isOk());
        var diagnostic = diagnostics.findByOsId(command.osId()).orElseThrow();
        diagnostics.save(diagnostic.addItem(item()));
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/conclusao"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        var first = outbox.acquire(Instant.now(), Duration.ofMinutes(1), 5).orElseThrow();
        assertThat(first.eventType()).isEqualTo("diagnostic.started.v1");
        assertThat(outbox.acquire(Instant.now(), Duration.ofMinutes(1), 5)).isEmpty();
        broker.publish(first);
        outbox.markPublished(first.eventId(), first.leaseToken(), Instant.now());
        receiveEvent();
        publisher.publishOne();
        var completed = receiveEvent();
        assertThat(completed.get("eventType").asString()).isEqualTo("diagnostic.completed.v1");
        assertThat(completed.get("correlationId").asString()).isEqualTo(command.correlationId().toString());
        JsonNode item = completed.get("payload").get("items").get(0);
        assertThat(item.get("quantidade").isString()).isTrue();
        assertThat(item.get("quantidade").asString()).isEqualTo("1.250");
        assertThat(item.get("precoPrevisto").asString()).isEqualTo("1234567890123456789012345678901234567890.00100");
        var startRepair = command("repair.start.v1", command.osId(), command.sagaId());
        handle(startRepair);
        var repair = repairs.findByOsId(command.osId()).orElseThrow();
        mvc.perform(post("/api/v1/reparos/" + repair.id() + "/inicio")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/reparos/" + repair.id() + "/conclusao")).andExpect(status().isOk());
        publisher.publishOne();
        assertThat(receiveEvent().get("eventType").asString()).isEqualTo("repair.started.v1");
        publisher.publishOne();
        var repairCompleted = receiveEvent();
        assertThat(repairCompleted.get("eventType").asString()).isEqualTo("repair.completed.v1");
        assertThat(repairCompleted.get("payload").get("repairId").asString()).isEqualTo(repair.id().toString());
        mvc.perform(post("/api/v1/reparos/" + repair.id() + "/conclusao")).andExpect(status().isConflict());
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(4);
        mvc.perform(get("/api/v1/execucoes/fila")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void restOutboxFailureRollsBackCompletionAndHistory() throws Exception {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().addItem(item()));
        var repair = repairs.save(Repair.create(UUID.randomUUID(), UUID.randomUUID(), diagnostic.id()).authorizeAndStart());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("outbox failure");
        }).when(outboxAdapter).save(any());
        mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/conclusao")).andExpect(status().isInternalServerError());
        mvc.perform(post("/api/v1/reparos/" + repair.id() + "/conclusao")).andExpect(status().isInternalServerError());
        assertThat(diagnostics.findById(diagnostic.id())).contains(diagnostic);
        assertThat(repairs.findById(repair.id())).contains(repair);
        assertThat(mongo.count(new Query(), ExecutionHistoryDocument.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isZero();
    }

    @Test
    void technicalFailuresRecordActualStateAndRequiredReasonEvents() {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start());
        diagnosticService.fail(diagnostic.id(), "Falha tecnica", UUID.randomUUID());
        var repair = repairs.save(Repair.create(UUID.randomUUID(), UUID.randomUUID(), diagnostic.id()).authorizeAndStart());
        repairService.fail(repair.id(), "Falha fisica", UUID.randomUUID());
        assertThat(diagnostics.findById(diagnostic.id()).orElseThrow().status()).isEqualTo(DiagnosticStatus.FAILED);
        assertThat(repairs.findById(repair.id()).orElseThrow().status()).isEqualTo(RepairStatus.FAILED);
        publisher.publishOne();
        publisher.publishOne();
        var envelopes = List.of(receiveEvent(), receiveEvent());
        assertThat(envelopes).extracting(node -> node.get("eventType").asString())
                .containsExactlyInAnyOrder("diagnostic.failed.v1", "repair.failed.v1");
        assertThat(envelopes).allSatisfy(node -> assertThat(node.get("payload").get("reason").asString()).isNotBlank());
    }

    @Test
    void concurrentRestCompletionsProduceOneHistoryAndOneOutboxEvent() throws Exception {
        var diagnostic = diagnostics.save(Diagnostic.create(UUID.randomUUID(), UUID.randomUUID()).start().addItem(item()));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> complete = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/diagnosticos/" + diagnostic.id() + "/conclusao")
                        .header("Authorization", "Bearer " + jwks.token("MECANICO")))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(complete);
            var second = executor.submit(complete);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(mongo.count(new Query(), OutboxEventDocument.class)).isEqualTo(1);
        assertThat(history.findByOsIdOrderByCreatedAt(diagnostic.osId())).hasSize(1);
    }

    @Test
    void messagingIndexesActuallyExistIncludingUniqueOsSequence() {
        var indexes = mongo.indexOps(OutboxEventDocument.class).getIndexInfo();
        assertThat(indexes).extracting(index -> index.getName())
                .contains("_id_", "outbox_pending", "outbox_lease", "outbox_os_sequence");
        var sequence = indexes.stream().filter(index -> index.getName().equals("outbox_os_sequence")).findFirst().orElseThrow();
        assertThat(sequence.isUnique()).isTrue();
        assertThat(sequence.getIndexFields()).extracting(field -> field.getKey()).containsExactly("osId", "sequence");
        assertThat(mongo.indexOps(InboxMessageDocument.class).getIndexInfo()).extracting(index -> index.getName())
                .contains("_id_", "inbox_os_received");
    }
}
