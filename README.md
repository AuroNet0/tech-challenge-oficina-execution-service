# tech-challenge-oficina-execution-service

Microsservico Execution Service do Tech Challenge FIAP - Fase 4, etapa 6.2.

## Objetivo

O Execution Service implementa o dominio de diagnostico tecnico e execucao dos reparos de uma ordem de servico, com persistencia propria MongoDB. O bootstrap de Spring Boot, seguranca inicial, OpenAPI, health checks e Docker permanece preservado.

Nao ha endpoints de negocio, RabbitMQ, Saga, Inbox, Outbox, JWT definitivo ou integracao com outros servicos nesta etapa. Nenhum evento e publicado.

## Responsabilidades futuras

- Consumir comandos do OS Service, como `diagnostic.start.v1`, `repair.start.v1` e `repair.cancel.v1`.
- Publicar eventos para a saga orquestrada pelo OS Service, como `diagnostic.started.v1`, `diagnostic.completed.v1`, `repair.started.v1`, `repair.completed.v1` e eventos de falha.
- Persistir dados proprios de diagnostico e reparo exclusivamente no MongoDB do Execution Service.
- Participar da arquitetura de microsservicos sem consultar diretamente bancos do OS Service ou Billing Service.

Os contratos futuros devem seguir a referencia do OS Service em `docs/events.md` e `docs/saga.md`.

## Tecnologias

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data MongoDB
- Spring Validation
- Spring Security
- Spring Boot Actuator
- Springdoc OpenAPI 3.1.1
- JUnit 5
- Mockito
- Testcontainers MongoDB
- JaCoCo
- Docker e Docker Compose

## Arquitetura

Pacote raiz: `com.oficina.execution`.

```text
src/main/java/com/oficina/execution/
├── ExecutionApplication.java
├── domain/
│   ├── model/
│   ├── enums/
│   └── exception/
├── application/
│   ├── usecase/
│   └── port/
│       ├── in/
│       └── out/
└── infrastructure/
    ├── persistence/
    ├── messaging/
    ├── web/
    ├── config/
    └── observability/
```

O dominio possui os modelos imutaveis `Diagnostic`, `DiagnosticItem`, `Repair` e `ExecutionHistory`, enums e `DomainException`, sem dependencia de Spring ou MongoDB. `application/port/out` define tres portas de repositorio. `infrastructure/persistence` separa `document`, `repository`, `adapter` e `mapper`.

## Configuracao MongoDB

A conexao usa variaveis de ambiente:

| Variavel | Padrao local |
| --- | --- |
| `MONGO_HOST` | `localhost` |
| `MONGO_PORT` | `27017` |
| `MONGO_DATABASE` | `oficina_execution` |
| `MONGO_USERNAME` | `oficina_execution` |
| `MONGO_PASSWORD` | `oficina_execution` |
| `MONGO_AUTH_DATABASE` | `admin` |

O banco padrao de desenvolvimento e `oficina_execution`. A propriedade usada pelo Spring Boot 4 e `spring.mongodb.uri`. Nao versionar credenciais reais.

## Execucao local

Com MongoDB disponivel:

```bash
./mvnw spring-boot:run
```

No Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

## Docker Compose

O Compose sobe MongoDB com autenticacao, volume persistente e a aplicacao. A aplicacao fica exposta em `8081` para coexistir com o OS Service na porta `8080`.

```bash
docker compose up --build -d
```

URLs principais:

- Health: `http://localhost:8081/actuator/health`
- Liveness: `http://localhost:8081/actuator/health/liveness`
- Readiness: `http://localhost:8081/actuator/health/readiness`
- Swagger UI: `http://localhost:8081/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`

## Seguranca

A configuracao inicial permite acesso publico apenas a:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/swagger-ui/**`
- `/v3/api-docs/**`

As demais rotas exigem autenticacao HTTP Basic gerada pelo Spring Security. Nao ha login proprio nem JWT definitivo nesta etapa.

## Testes e cobertura

Executar a suite completa:

```bash
./mvnw clean verify
```

No Windows PowerShell:

```powershell
.\mvnw.cmd clean verify
```

Os testes usam Testcontainers com MongoDB para validar:

- Inicializacao do contexto Spring.
- Conectividade e operacoes basicas com MongoDB.
- Health checks, incluindo readiness com MongoDB.
- Seguranca dos endpoints publicos e protegidos.
- Disponibilidade da documentacao OpenAPI.

O relatorio JaCoCo e gerado em:

```text
target/site/jacoco/index.html
```

A cobertura minima configurada e de 80% por linhas de codigo. A unica exclusao e a classe de bootstrap `ExecutionApplication`, que nao contem regra de negocio.

## Dominio e estados

- Diagnostic nasce PENDING; start permite PENDING -> IN_PROGRESS; complete e fail permitem IN_PROGRESS -> COMPLETED ou FAILED. Cancelamento e permitido em PENDING, IN_PROGRESS ou FAILED, antes da conclusao. COMPLETED e CANCELLED nao aceitam edicao. FAILED so admite cancelamento.
- Diagnostico concluido exige pelo menos um item valido. Itens so podem ser adicionados durante IN_PROGRESS; itemId deve ser unico apos strip. Quantidade deve ser positiva e precoPrevisto nao negativo. Descricao e identificador nao podem estar vazios. Nao ha calculo de orcamento.
- Repair nasce PENDING. authorizeAndStart representa explicitamente a autorizacao futura recebida por repair.start.v1: PENDING -> IN_PROGRESS. Nao existe setter generico de status nem operacao start sem autorizacao. complete e fail exigem IN_PROGRESS.
- cancel confirma cancelamento apenas em PENDING. cancellationOutcome distingue CANCELLATION_ALLOWED (PENDING), CONFIRMED (CANCELLED), MANUAL_INTERVENTION_REQUIRED (IN_PROGRESS ou FAILED) e COMPENSATION_FAILED (COMPLETED), sem alterar o estado fisico. COMPLETED e CANCELLED sao terminais. A confirmacao de compensacao de trabalho iniciado depende de politica e evidencias futuras; nao e simulada nesta etapa.
- Operacoes retornam novas instancias; o chamador deve utilizar o retorno, inclusive de save (que devolve a nova version). Os construtores validam snapshots para reconstituicao; os futuros casos de uso devem utilizar as operacoes de dominio para transicoes e validar a origem da autorizacao.
- ExecutionHistory e imutavel e sua persistencia usa insert: salvar novamente o mesmo id falha, sem sobrescrever o registro. Referencias tecnicas sao opcionais para notas da OS; futuros casos de uso devem informa-las quando aplicaveis. Descricoes devem conter somente informacao tecnica necessaria, sem dados pessoais, credenciais ou outros dados sensiveis.

## Colecoes e relacionamentos

| Colecao | Conteudo | Indices adicionais |
| --- | --- | --- |
| diagnostics | id, osId, sagaId, status, observacoes, items embutidos, createdAt, updatedAt, completedAt, version | osId unico (unique_os); sagaId (saga) |
| work_orders | id, osId, sagaId, diagnosticId, status, observacoes, startedAt, completedAt, createdAt, updatedAt, version | osId unico (unique_os); sagaId (saga) |
| execution_history | id, osId, diagnosticId e repairId opcionais, eventType, description, createdAt, correlationId opcional | osId ASC + createdAt ASC (history_os_created) |

O id e persistido como _id, com indice unico nativo do MongoDB. UUIDs sao strings canonicas, inclusive nos relacionamentos; nao ha DBRef. Um diagnostico e um reparo por OS sao garantidos pelos indices unicos. Saga nao e unica. Historico e ordenado por createdAt crescente, usando id como desempate.

Os indices anotados sao efetivamente criados com spring.data.mongodb.auto-index-creation=true; os testes consultam seus metadados e exercitam violacoes de unicidade. MongoDB e o unico banco. A existencia da OS nao e consultada. A consistencia entre osId, sagaId e diagnosticId e a conclusao do diagnostico referenciado deverao ser verificadas explicitamente pelos futuros casos de uso; os repositorios nao simulam foreign keys.

DiagnosticDocument e RepairDocument usam @Version Long, inicialmente null, para controle otimista. Atualizacoes com versao obsoleta lancam OptimisticLockingFailureException e nao sobrescrevem dados. Cada save e atomico apenas no proprio documento. Instantes produzidos pelas fabricas e transicoes usam precisao de milissegundos, compativel com BSON Date.

## Compatibilidade com OS Service

Referencias consultadas na branch homolog:

- [docs/events.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md)
- [docs/saga.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md)

Os nomes itemId, descricao, tipo, quantidade, unidadePreco e precoPrevisto correspondem ao contrato diagnostic.completed.v1. Tipos e unidades preservam SERVICO/PECA/MATERIAL e UNIDADE/HORA/KIT. No dominio, numeros sao BigDecimal; no documento, quantidade e precoPrevisto sao strings decimais produzidas por toPlainString, preservando precisao e escala sem limitar valores a Decimal128. precoPrevisto e estimativa unitaria para o Billing futuro.

O futuro mapper de mensagens devera igualmente emitir quantidade e precoPrevisto como strings decimais (nao serializar o BigDecimal diretamente como numero), preservar osId, sagaId e correlationId e montar o envelope documentado pelo OS Service. Os documentos MongoDB nao sao envelopes RabbitMQ. Falhas e cancelamentos seguem a distincao da saga entre repair.failed.v1, repair.cancelled.v1 e repair.cancellation-failed.v1; nenhuma dessas mensagens e produzida nesta etapa.

## Atomicidade futura e limites

O futuro consumidor devera gravar estado tecnico, historico, Inbox e Outbox na mesma transacao MongoDB, configurando MongoTransactionManager e replica set. A publicacao RabbitMQ devera ocorrer posteriormente pela Outbox. Nao assumir atomicidade entre colecoes com saves independentes.

Testcontainers usa MongoDB 8 em replica set e valida rollback real entre diagnostics e execution_history, alem de persistencia, precisao decimal, reconstrucao, indices, ordenacao, unicidade e concorrencia otimista. Os testes de bootstrap permanecem preservados.

O Compose de desenvolvimento preserva o MongoDB autenticado standalone do bootstrap: suporta esta etapa, mas devera ser configurado com replica set antes de implementar transacoes no processamento de mensagens. Nao ha transacao de aplicacao ou consumidor habilitado atualmente.

Nao foram implementados casos de uso de integracao, controllers de negocio, mensageria, Inbox/Outbox, saga, Billing, pagamentos, JWT definitivo, infraestrutura cloud ou alteracao de status da OS. Nao ha dados ficticios permanentes.
