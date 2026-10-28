# tech-challenge-oficina-execution-service

Microsservico Execution Service do Tech Challenge FIAP - Fase 4, etapa 6.4.

## Objetivo

O Execution Service implementa o dominio de diagnostico tecnico e execucao dos reparos de uma ordem de servico, com persistencia propria MongoDB. O bootstrap de Spring Boot, seguranca inicial, OpenAPI, health checks e Docker permanece preservado.

A API REST permite consultar e atualizar tecnicamente diagnosticos e reparos existentes. Comandos RabbitMQ do OS Service disponibilizam trabalhos PENDING na fila operacional; um mecanico autorizado inicia explicitamente via REST. Inbox, estado tecnico, historico e Outbox sao persistidos atomicamente no MongoDB; eventos sao publicados posteriormente pela Outbox. A Saga continua exclusivamente no OS Service. Consulte [fila operacional 9.1.2](docs/operational-queue-9.1.2.md) para inspecao, estados, endpoints, exemplos, autorizacao e impacto no E2E 8.3.5, e [docs/messaging.md](docs/messaging.md) para contratos de mensagens.

## Responsabilidades

- Consumir comandos do OS Service, como `diagnostic.start.v1`, `repair.start.v1` e `repair.cancel.v1`.
- Publicar eventos para a saga orquestrada pelo OS Service, como `diagnostic.started.v1`, `diagnostic.completed.v1`, `repair.started.v1`, `repair.completed.v1` e eventos de falha.
- Integrar os estados tecnicos ja persistidos no MongoDB com comandos e eventos.
- Participar da arquitetura de microsservicos sem consultar diretamente bancos do OS Service ou Billing Service.

Os contratos seguem a referencia do OS Service em `docs/events.md` e `docs/saga.md`.

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
- RabbitMQ, Transactional Inbox e Outbox
- Testcontainers MongoDB e RabbitMQ
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

O dominio possui os modelos imutaveis `Diagnostic`, `DiagnosticItem`, `Repair` e `ExecutionHistory`, enums e `DomainException`, sem dependencia de Spring ou MongoDB. `application/port/in` define as operacoes dos casos de uso; `application/usecase` implementa consultas e alteracoes transacionais; `application/port/out` define tres portas de repositorio. `infrastructure/persistence` separa `document`, `repository`, `adapter` e `mapper`. Controllers finos, requests validados, responses e tratamento HTTP estao em `infrastructure/web`; documentos MongoDB nao sao expostos.

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

Com MongoDB disponivel, defina EXECUTION_JWT_ISSUER, EXECUTION_JWT_AUDIENCE e EXECUTION_JWT_JWKS_URI (HTTPS) conforme o Auth do ambiente. Essas variaveis sao obrigatorias, inclusive no Compose; nao ha valores produtivos padrao. Entao execute:

```bash
./mvnw spring-boot:run
```

No Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

## Docker Compose

O Compose sobe MongoDB autenticado em replica set de um membro (rs0), volume persistente e a aplicacao. A aplicacao fica exposta em `8081` para coexistir com o OS Service na porta `8080`.

```bash
docker compose up --build -d
```

URLs principais:

- Health: `http://localhost:8081/actuator/health`
- Liveness: `http://localhost:8081/actuator/health/liveness`
- Readiness: `http://localhost:8081/actuator/health/readiness`
- Swagger/OpenAPI e `/internal/bootstrap` permanecem bloqueados.

## Seguranca

A etapa 8.3.3 permite acesso sem JWT humano apenas aos GETs de probes, sem detalhes de componentes:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`

Operacoes tecnicas exigem Bearer JWT RS256 valido, ROLE_MECANICO e capacidade especifica na OS atribuida. Basic nao e aceito. O Execution resolve o osId do agregado e consulta uma porta de autorizacao. O perfil mtls usa o canal interno do OS com certificado de servico e JWT humano; sem o perfil/canal configurado, o adapter padrao bloqueia operacoes (503). CLIENTE, ATENDENTE e GERENTE nao recebem acesso tecnico pelo papel; demais rotas/metodos sao negados por padrao. RabbitMQ continua independente de JWT humano.

Arquitetura, matriz completa, contrato proposto, limitacoes e evidencias: [autorizacao 8.3.3](docs/security/execution-authorization-8.3.3.md).

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

- Diagnostic nasce PENDING; start permite PENDING -> IN_PROGRESS; complete e fail permitem IN_PROGRESS -> COMPLETED ou FAILED. Cancelamento e permitido somente em PENDING ou IN_PROGRESS. FAILED preserva a falha e a justificativa, sem conversao implicita para CANCELLED. COMPLETED, CANCELLED e FAILED nao aceitam edicao.
- Diagnostico concluido exige pelo menos um item valido. Itens so podem ser adicionados durante IN_PROGRESS; itemId deve ser unico apos strip. Quantidade deve ser positiva e precoPrevisto nao negativo. Descricao e identificador nao podem estar vazios. Nao ha calculo de orcamento.
- Repair nasce PENDING quando repair.start.v1 autoriza e disponibiliza o trabalho. O inicio operacional REST autorizado aplica authorizeAndStart: PENDING -> IN_PROGRESS. complete e fail exigem IN_PROGRESS.
- cancel confirma cancelamento apenas em PENDING. cancellationOutcome distingue CANCELLATION_ALLOWED (PENDING), CONFIRMED (CANCELLED), MANUAL_INTERVENTION_REQUIRED (IN_PROGRESS ou FAILED) e COMPENSATION_FAILED (COMPLETED), sem alterar o estado fisico. COMPLETED e CANCELLED sao terminais. A confirmacao de compensacao de trabalho iniciado depende de politica e evidencias futuras; nao e simulada nesta etapa.
- Operacoes retornam novas instancias; o chamador deve utilizar o retorno, inclusive de save (que devolve a nova version). Os construtores validam snapshots para reconstituicao; os futuros casos de uso devem utilizar as operacoes de dominio para transicoes e validar a origem da autorizacao.
- ExecutionHistory e imutavel e sua persistencia usa insert: salvar novamente o mesmo id falha, sem sobrescrever o registro. Referencias tecnicas sao opcionais para notas da OS; futuros casos de uso devem informa-las quando aplicaveis. Descricoes devem conter somente informacao tecnica necessaria, sem dados pessoais, credenciais ou outros dados sensiveis.

## Colecoes e relacionamentos

| Colecao | Conteudo | Indices adicionais |
| --- | --- | --- |
| diagnostics | id, osId, sagaId, status, observacoes, items embutidos, createdAt, updatedAt, completedAt, version | osId unico (unique_os); sagaId (saga) |
| work_orders | id, osId, sagaId, diagnosticId, status, observacoes, startedAt, completedAt, createdAt, updatedAt, version | osId unico (unique_os); sagaId (saga) |
| execution_history | id, osId, diagnosticId e repairId opcionais, eventType, description, createdAt, correlationId opcional | osId ASC + createdAt ASC (history_os_created) |
| inbox_messages | eventId, envelope, contexto, status e timestamps | eventId como _id unico; osId + receivedAt |
| outbox_events | evento, envelope, contexto, tentativas, lease e sequencia | eventId como _id unico; status + nextAttemptAt; status + lockedUntil; osId + sequence unico |

O id e persistido como _id, com indice unico nativo do MongoDB. UUIDs sao strings canonicas, inclusive nos relacionamentos; nao ha DBRef. Um diagnostico e um reparo por OS sao garantidos pelos indices unicos. Saga nao e unica. Historico e ordenado por createdAt crescente, usando id como desempate.

Os indices anotados sao efetivamente criados com spring.data.mongodb.auto-index-creation=true; os testes consultam seus metadados e exercitam violacoes de unicidade. MongoDB e o unico banco. A existencia da OS nao e consultada. O handler valida sagaId e diagnostico concluido/consistente antes de autorizar reparo; os repositorios nao simulam foreign keys.

DiagnosticDocument e RepairDocument usam @Version Long, inicialmente null, para controle otimista. Atualizacoes com versao obsoleta lancam OptimisticLockingFailureException e nao sobrescrevem dados. Cada save e atomico apenas no proprio documento. Instantes produzidos pelas fabricas e transicoes usam precisao de milissegundos, compativel com BSON Date.

## Compatibilidade com OS Service

Referencias consultadas na branch homolog:

- [docs/events.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md)
- [docs/saga.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md)

Os nomes itemId, descricao, tipo, quantidade, unidadePreco e precoPrevisto correspondem ao contrato diagnostic.completed.v1. Tipos e unidades preservam SERVICO/PECA/MATERIAL e UNIDADE/HORA/KIT. No dominio, numeros sao BigDecimal; no documento, quantidade e precoPrevisto sao strings decimais produzidas por toPlainString, preservando precisao e escala sem limitar valores a Decimal128. precoPrevisto e estimativa unitaria para o Billing futuro.

O mapper de mensagens emite quantidade e precoPrevisto como strings decimais, preserva osId, sagaId e correlationId e monta o envelope documentado pelo OS Service. Falhas e cancelamentos seguem a distincao entre repair.failed.v1, repair.cancelled.v1 e repair.cancellation-failed.v1. A Outbox armazena o envelope completo, com eventId proprio e estavel entre tentativas.

## Atomicidade e limites

MongoTransactionManager esta configurado. Os casos de uso de escrita usam @Transactional: alteracao de Diagnostic ou Repair e insercao de ExecutionHistory confirmam ou revertem juntos. Falhas de historico nao deixam o estado tecnico atualizado. Versoes obsoletas e conflitos de escrita de transacoes retornam HTTP 409, sem retry automatico. O cliente deve consultar novamente antes de decidir como reaplicar sua alteracao.

O consumidor inclui Inbox e Outbox na mesma transacao do estado tecnico e historico. As conclusoes REST incluem a Outbox na transacao. A publicacao RabbitMQ ocorre posteriormente, sem transacao MongoDB aberta durante o confirm. O historico tecnico nao coordena a Saga.

Testcontainers usa MongoDB 8 em replica set e valida rollback real entre diagnostics e execution_history, alem de persistencia, precisao decimal, reconstrucao, indices, ordenacao, unicidade e concorrencia otimista. Os testes de bootstrap permanecem preservados.

O script docker/mongo-entrypoint.sh gera uma chave interna em /data/db/.replica-keyfile somente quando ausente, com permissao 400 e proprietario mongodb. O healthcheck autentica, inicia rs0 somente se ainda nao inicializado e aguarda isWritablePrimary antes de liberar a aplicacao. Reutiliza o volume anterior sem apagar os dados. As credenciais de volumes existentes devem continuar correspondendo as variaveis configuradas.

O membro anuncia mongodb:27017. A URI usa replicaSet=rs0 e directConnection=true para funcionar tanto no Compose quanto com a aplicacao executada no host via porta publicada. Esse replica set de um membro e destinado ao desenvolvimento, sem alta disponibilidade. Arquivos shell sao versionados com LF para funcionar tambem em checkouts Windows.

Nao foram implementados outro orquestrador de Saga, Billing, pagamentos, infraestrutura cloud ou alteracao direta de status da OS. Nao ha dados ficticios permanentes. JWT e politica HTTP foram acrescentados na etapa 8.3.3; a fila reutiliza a autorizacao distribuida atual com OS.

## API REST tecnica

Todos os endpoints abaixo exigem JWT RS256, papel MECANICO e autorizacao atual da OS para a capacidade solicitada. As respostas de sucesso dependem dessa decisao e da configuracao do canal mtls existente. Os exemplos Bearer ilustram o contrato HTTP, sem ativar a integracao OS nem contornar a autorizacao.

| Metodo | Endpoint | Operacao |
| --- | --- | --- |
| GET | /api/v1/diagnosticos/{id} | Consultar diagnostico por UUID |
| GET | /api/v1/diagnosticos?osId={uuid} | Consultar unico diagnostico da OS |
| POST | /api/v1/diagnosticos/{id}/itens | Adicionar item em IN_PROGRESS |
| PATCH | /api/v1/diagnosticos/{id}/observacoes | Atualizar observacoes em PENDING ou IN_PROGRESS |
| POST | /api/v1/diagnosticos/{id}/conclusao | Concluir IN_PROGRESS com itens validos |
| GET | /api/v1/reparos/{id} | Consultar reparo por UUID |
| GET | /api/v1/reparos?osId={uuid} | Consultar unico reparo da OS |
| PATCH | /api/v1/reparos/{id}/observacoes | Atualizar observacoes em PENDING ou IN_PROGRESS |
| POST | /api/v1/reparos/{id}/conclusao | Concluir somente IN_PROGRESS |
| GET | /api/v1/execucoes/historico?osId={uuid} | Historico cronologico, ou lista vazia |
| GET | /api/v1/execucoes/fila | Pendencias autorizadas, mais antigas primeiro |
| POST | /api/v1/diagnosticos/{id}/inicio | Iniciar diagnostico PENDING (DIAGNOSTIC_WRITE) |
| POST | /api/v1/reparos/{id}/inicio | Iniciar reparo PENDING (REPAIR_WRITE) |

Consultas de agregado retornam um objeto e HTTP 404 quando ausente, inclusive busca por osId. Alteracoes bem-sucedidas retornam HTTP 200 e o agregado atualizado, incluindo version. Historico retorna uma lista. Campos de resposta incluem os identificadores, estado, observacoes e timestamps; itens retornam quantidade e precoPrevisto como strings decimais, sem arredondamento.

A API opera somente sobre agregados existentes. A criacao depende de diagnostic.start.v1 e repair.start.v1; o inicio operacional exige os endpoints /inicio, sem corpo ou usuarioId. Nao existem endpoints de criacao, autorizacao do OS, cancelamento ou alteracao generica de status. PENDING nao pode ser concluido. O perfil mtls reutiliza o canal de autorizacao atual do OS; sem esse canal o adapter padrao retorna 503.

### Adicionar item

Exemplo (substitua ID e TOKEN pelo JWT valido do Auth, sem registrar o token):

```bash
curl -H "Authorization: Bearer TOKEN" -X POST \
  "http://localhost:8081/api/v1/diagnosticos/ID/itens" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{"itemId":"item-001","descricao":"Troca de pastilhas de freio","tipo":"PECA","quantidade":"2","unidadePreco":"UNIDADE","precoPrevisto":"150.00"}'
```

Quantidade e preco sao strings decimais sem notacao exponencial: quantidade positiva e preco nao negativo. Ambos sao convertidos para BigDecimal, sem calculo de orcamento. itemId duplicado apos normalizacao retorna 409; dados obrigatorios ausentes ou invalidos retornam 400.

### Atualizar observacoes e concluir

```bash
curl -H "Authorization: Bearer TOKEN" -X PATCH \
  "http://localhost:8081/api/v1/reparos/ID/observacoes" \
  -H "Content-Type: application/json" \
  -d '{"observacoes":"Teste tecnico realizado"}'

curl -H "Authorization: Bearer TOKEN" -X POST \
  "http://localhost:8081/api/v1/reparos/ID/conclusao"
```

observacoes deve estar presente e nao ser null; string vazia permite limpar o texto. O mesmo formato atende diagnosticos.

X-Correlation-Id e opcional nas alteracoes e deve ser UUID quando informado; e preservado no historico. Nas conclusoes, sua ausencia recupera a correlacao do ultimo comando processado da OS. Cada alteracao bem-sucedida registra referencias tecnicas, descricao, createdAt e eventType (diagnostic.item-added, diagnostic.observations-updated, diagnostic.completed.v1, repair.observations-updated ou repair.completed.v1). O texto livre de observacoes nao e duplicado no historico.

Concluir diagnostico ou reparo salva o estado, historico e diagnostic.completed.v1 ou repair.completed.v1 na Outbox atomicamente. O worker publica esses eventos para a Saga do OS Service. Falha de Outbox causa rollback da conclusao; indisponibilidade do broker nao impede o commit local. A entrega e at-least-once e o OS Service deduplica pelo eventId.

### Erros

Erros de aplicacao usam application/problem+json (ProblemDetail), por exemplo:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Invalid repair transition"
}
```

- 400: UUID, enum, JSON ou campos invalidos.
- 401: autenticacao ausente ou invalida, tratada pelo Spring Security.
- 403: papel, escopo/capacidade ou rota/metodo nao autorizado.
- 404: agregado ou rota inexistente.
- 409: regra de dominio, item duplicado, chave unica ou concorrencia.
- 500: falha inesperada, com mensagem generica sem detalhes internos.
- 503: canal de autorizacao operacional ausente/indisponivel; nenhuma mutacao.

### Validacao e relatorios

```bash
./mvnw clean verify
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose exec execution-service curl -fsS http://localhost:8080/actuator/health/readiness
```

Para iniciar apenas o MongoDB transacional: docker compose up -d --wait mongodb; depois execute ./mvnw spring-boot:run no host.

Os testes usam Testcontainers MongoDB 8 em replica set e nao dependem do MongoDB do Compose. Incluem HTTP autenticado, validacao de entrada, ausencia de rotas de autorizacao, precisao decimal, persistencia e historico, rollback apos falha de insercao do historico e duas alteracoes HTTP concorrentes (uma confirma e outra retorna 409).

clean verify gera target/site/jacoco/index.html (abrir no navegador) e target/site/jacoco/jacoco.xml, verificando minimo de 80% de linhas. Os resultados individuais ficam em target/surefire-reports.
