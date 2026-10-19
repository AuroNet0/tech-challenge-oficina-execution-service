# tech-challenge-oficina-execution-service

Bootstrap do microsservico Execution Service do Tech Challenge FIAP - Fase 4.

## Objetivo

O Execution Service sera responsavel pelo ciclo de diagnostico e execucao dos reparos de uma ordem de servico. Nesta etapa o projeto entrega apenas a base tecnica do microsservico: Spring Boot, MongoDB, seguranca inicial, OpenAPI, health checks, Docker, testes e JaCoCo.

Nao ha endpoints de negocio, entidades `Diagnostic` ou `Repair`, RabbitMQ, Saga, Inbox, Outbox, JWT definitivo ou integracao com outros servicos nesta etapa.

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

As pastas de dominio e aplicacao estao preservadas sem classes artificiais. Classes futuras de dominio nao devem depender de Spring ou MongoDB.

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
