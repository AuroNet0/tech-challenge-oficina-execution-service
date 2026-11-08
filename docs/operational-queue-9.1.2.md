# Fila operacional — 9.1.2

## Inspeção anterior à implementação

- Branch `homolog`, árvore inicialmente limpa. Não havia AGENTS.md no repositório.
- Diagnostic e Repair já possuem PENDING, IN_PROGRESS, COMPLETED, FAILED e CANCELLED. O consumidor criava e iniciava imediatamente ambos, gravando histórico e `*.started.v1` na Outbox na mesma transação da Inbox.
- `CommandConsumer` preserva ACK, retry e DLQ; `CommandHandler` deduplica eventId pela Inbox e OS pelos índices únicos. Valida sagaId e diagnóstico concluído antes de disponibilizar reparo.
- MongoDB é replica set, com MongoTransactionManager, @Transactional e @Version. Estado, histórico, Inbox e Outbox participam da transação; publicação é posterior.
- JWT RS256/JWKS e ROLE_MECANICO são obrigatórios. TechnicalAuthorization resolve a OS do agregado e consulta atribuição/capacidade atual. O perfil mtls já consulta o OS com certificado de serviço e JWT humano; o adapter padrão falha fechado (503).
- `repair.cancel.v1` cancela PENDING; IN_PROGRESS/FAILED exigem intervenção manual e COMPLETED não permite compensação automática. Reentrega do mesmo eventId não repete efeitos.
- Inspecionados no OS irmão: ResourceAuthorization, InternalAuthorizationController, SagaEventHandler, SagaStateMachine e docs/saga.md. A decisão técnica verifica atribuição/capacidade sem exigir EM_DIAGNOSTICO/EM_EXECUCAO. A Saga permanece WAITING_DIAGNOSTIC/WAITING_REPAIR até conclusão; started atualiza o estado global. Portanto pode aguardar início humano sem alteração no OS ou novos estados globais.
- O E2E 8.3.5 foi localizado em `../tech-challenge-oficina-billing-service/integration/8.3.5/runner.py`: após obter diagnostic_id (linha 285), é necessário chamar POST /api/v1/diagnosticos/{id}/inicio antes de aguardar EM_DIAGNOSTICO (linhas 286–287) e adicionar itens (linha 330); após obter repair (linha 355), chamar POST /api/v1/reparos/{id}/inicio antes de aguardar EM_EXECUCAO (linha 356), usando o mecânico já provisionado e suas capacidades WRITE. A espera EM_DIAGNOSTICO para osOther deve passar a esperar RECEBIDA com trabalho PENDING, pois esse mecânico não possui atribuição nessa OS; os testes de acesso negado devem continuar. Alternativamente, um segundo mecânico explicitamente atribuído poderia iniciar osOther. O teste de recuperação de broker apenas exige agregado existente, permanecendo compatível com PENDING. Harness integrado não é alterado nem executado nesta etapa. Suas evidências anteriores não validam esta mudança.

## Operação

RabbitMQ transporta comandos; a fila operacional é uma consulta aos agregados existentes em MongoDB. Comandos start disponibilizam PENDING e registram histórico local `diagnostic.queued`/`repair.queued`, sem evento de início. O reparo pendente representa autorização recebida do OS. Não existe criação REST de trabalho.

`GET /api/v1/execucoes/fila` lista somente PENDING autorizados com DIAGNOSTIC_READ ou REPAIR_READ, ordenados por createdAt crescente, tipo e id como desempate. Cada item contém apenas id, osId, tipo, status e createdAt. Atribuições negadas são omitidas; indisponibilidade de autorização retorna 503.

`GET /api/v1/diagnosticos/{id}` e `GET /api/v1/reparos/{id}` consultam também trabalhos em execução e terminais com a autorização existente.

`POST /api/v1/diagnosticos/{id}/inicio` exige DIAGNOSTIC_WRITE; `POST /api/v1/reparos/{id}/inicio` exige REPAIR_WRITE. Não recebem usuarioId nem corpo. O ator é extraído do JWT, com autorização atual na OS. Retornam 200 e o agregado atualizado. Aceitam X-Correlation-Id opcional; sem ele recuperam a correlação da Inbox.

```bash
curl -H "Authorization: Bearer TOKEN" http://localhost:8081/api/v1/execucoes/fila
curl -X POST -H "Authorization: Bearer TOKEN" http://localhost:8081/api/v1/diagnosticos/ID/inicio
curl -H "Authorization: Bearer TOKEN" http://localhost:8081/api/v1/diagnosticos/ID
```

O início faz PENDING -> IN_PROGRESS e grava histórico + evento started existente na Outbox atomicamente. @Version e conflitos de escrita MongoDB impedem dois commits de início; repetição/transição incompatível retorna 409. Falhas de histórico/Outbox revertem a transação inteira. Não há retry automático HTTP.

Agregados existentes mantêm seu estado e histórico; trabalhos antigos IN_PROGRESS não são recolocados em PENDING. Índices status + createdAt + _id suportam as consultas de pendências nas duas coleções existentes. A consulta usa verificação individual de autorização por OS e não implementa paginação ou distribuição de mecânicos nesta etapa.

Conclusão utiliza os endpoints existentes e emite os eventos existentes. COMPLETED, CANCELLED e FAILED ficam fora das pendências; IN_PROGRESS é consultável pelos endpoints de situação e também não aguarda início. Cancelar reparo pendente continua emitindo repair.cancelled.v1; iniciar trabalho cancelado retorna 409. Contratos RabbitMQ, Billing, retries e DLQ permanecem preservados.

401 indica JWT ausente/inválido; 403 papel/atribuição/capacidade negada em operações individuais; 404 agregado ausente; 409 transição ou concorrência; 503 autorização indisponível. Não há novos estados globais nem mudança no protocolo da Saga. Enquanto aguarda, a OS mantém seu estado anterior (RECEBIDA ou AGUARDANDO_APROVACAO).

## Arquivos da entrega

```text
README.md
docs/messaging.md
docs/operational-queue-9.1.2.md
src/main/java/com/oficina/execution/application/port/in/DiagnosticUseCase.java
src/main/java/com/oficina/execution/application/port/in/RepairUseCase.java
src/main/java/com/oficina/execution/application/port/in/OperationalQueueUseCase.java
src/main/java/com/oficina/execution/application/port/out/DiagnosticRepositoryPort.java
src/main/java/com/oficina/execution/application/port/out/RepairRepositoryPort.java
src/main/java/com/oficina/execution/application/usecase/CommandHandler.java
src/main/java/com/oficina/execution/application/usecase/DiagnosticService.java
src/main/java/com/oficina/execution/application/usecase/RepairService.java
src/main/java/com/oficina/execution/application/usecase/OperationalQueueService.java
src/main/java/com/oficina/execution/infrastructure/config/SecurityConfig.java
src/main/java/com/oficina/execution/infrastructure/persistence/adapter/DiagnosticRepositoryAdapter.java
src/main/java/com/oficina/execution/infrastructure/persistence/adapter/RepairRepositoryAdapter.java
src/main/java/com/oficina/execution/infrastructure/persistence/document/DiagnosticDocument.java
src/main/java/com/oficina/execution/infrastructure/persistence/document/RepairDocument.java
src/main/java/com/oficina/execution/infrastructure/persistence/repository/DiagnosticMongoRepository.java
src/main/java/com/oficina/execution/infrastructure/persistence/repository/RepairMongoRepository.java
src/main/java/com/oficina/execution/infrastructure/web/controller/DiagnosticController.java
src/main/java/com/oficina/execution/infrastructure/web/controller/RepairController.java
src/main/java/com/oficina/execution/infrastructure/web/controller/OperationalQueueController.java
src/test/java/com/oficina/execution/application/usecase/CommandHandlerTest.java
src/test/java/com/oficina/execution/infrastructure/messaging/MessagingIntegrationTest.java
src/test/java/com/oficina/execution/infrastructure/persistence/DomainPersistenceIntegrationTest.java
src/test/java/com/oficina/execution/infrastructure/web/ExecutionApiIntegrationTest.java
```

Os testes existentes foram ampliados, sem novo framework: CommandHandlerTest verifica entrada PENDING sem started; MessagingIntegrationTest exercita RabbitMQ real, deduplicação, cancelamento antes do início, conclusão, Outbox e rollback; ExecutionApiIntegrationTest verifica fila ordenada/autorizada, início por ambos os tipos, dois mecânicos concorrentes (200/409), repetição, negações HTTP e rollback de histórico/Outbox após inserção; DomainPersistenceIntegrationTest valida os índices efetivamente criados no MongoDB. Regressões técnicas, segurança, publisher, retry e DLQ continuam na suíte completa.

## Resultado da validação

- `mvn clean verify`: BUILD SUCCESS em 2026-10-28, Java 21.0.12.1, Maven 3.10.0, MongoDB e RabbitMQ reais via Testcontainers. Execução final: 2min58s.
- 157 testes, zero falhas, zero erros, zero ignorados. Inclui o cenário adicional de cancelamento antes do início e verificação dos índices da fila.
- JaCoCo LINE: 877 linhas cobertas, 50 não cobertas, total 927, cobertura 94,61%. Gate original de 80% preservado e aprovado, sem novas exclusões.
- Evidências locais: `target/verify-final.log`, `target/surefire-reports/` e `target/site/jacoco/index.html` / `jacoco.xml`. Diretório target ignorado pelo Git.
- `git diff --check` sem erros. Branch homolog preservada; 22 arquivos rastreados modificados e quatro novos, sem stage, commit ou push. A árvore inicial estava limpa.
- Nenhuma alteração no OS Service, Billing, harness integrado, Terraform ou CI/CD. Nenhum deploy. E2E distribuído 8.3.5 não executado: adaptações necessárias estão descritas na inspeção.

A revisão pode avaliar a mudança sem executar infraestrutura integrada. Para uso real, o perfil mtls e o canal de autorização OS existentes precisam estar configurados; as decisões de atribuição/capacidade foram substituídas por mocks apenas nos testes, conforme a infraestrutura anterior.
