# Etapa 6.5 — OS + Execution reais

Este ambiente executa os dois servicos da branch homolog com PostgreSQL 17, MongoDB 8 em replica set rs0 e **um unico RabbitMQ 4 compartilhado**. Nao modifica os Compose originais, application.yml ou codigo Java.

## Pre-requisitos

- Repositorios irmaos: tech-challenge-oficina-os-service e tech-challenge-oficina-execution-service.
- Ambos na branch homolog.
- Docker Desktop/Engine com Compose v2, PowerShell e Java 21 para as suites Maven.
- Docker e Git no PATH; no Windows os caminhos padrao sao utilizados como fallback.
- Portas livres 18080 (OS), 18081 (Execution) e 18572 (management RabbitMQ), publicadas somente em 127.0.0.1. PostgreSQL, MongoDB e AMQP nao sao publicados no host.
- Recursos suficientes para os dois builds e cinco containers.

Credenciais integration/integration_only sao fixtures locais deste ambiente isolado. Nunca reutilizar em producao. Os usuarios HTTP continuam sendo os usuarios padrao configuraveis do Spring Security, sem nova autenticacao ou alteracao no codigo.

## Executar as suites existentes primeiro

No PowerShell, com JAVA_HOME apontando para Java 21 e Docker em execucao:

    .\mvnw.cmd clean verify
    Push-Location ..\tech-challenge-oficina-os-service
    .\mvnw.cmd clean verify
    Pop-Location

Em Bash, o equivalente e ./mvnw clean verify em cada repositorio. Nao foram criados perfis para ignorar testes.

## Executar o cenario real

Na raiz do Execution:

    powershell -NoProfile -ExecutionPolicy Bypass -File integration/run.ps1

A politica de execucao e especifica do processo; nao modifica a configuracao permanente do Windows. Em uma sessao que ja permite scripts, basta .\integration\run.ps1.

O script constroi e inicia integration/compose.yaml, valida os estados, executa as APIs reais, coleta evidencias e encerra exclusivamente o projeto oficina-integration-65 com seus volumes de teste. Os Compose e volumes de desenvolvimento permanecem intocados.

Opcoes:

    .\integration\run.ps1 -SkipBuild
    .\integration\run.ps1 -KeepEnvironment
    .\integration\run.ps1 -ProjectName oficina-integration-65-manual

-SkipBuild reutiliza imagens existentes; nao pula verificacoes. -KeepEnvironment preserva o ambiente para inspecao. O prefixo do projeto e validado para evitar confusao com outros ambientes. Nao execute este script com dados de producao.

Portas podem ser alteradas antes da execucao:

    $env:INTEGRATION_OS_PORT = "28080"
    $env:INTEGRATION_EXECUTION_PORT = "28081"
    $env:INTEGRATION_RABBIT_MANAGEMENT_PORT = "28572"

Nao ha nomes fixos de containers nem ligacao com os brokers dos Compose de desenvolvimento.

## O que o teste verifica

1. Cinco containers healthy; MongoDB isWritablePrimary=true; health/readiness dos dois servicos.
2. Exchanges topic duraveis oficina.commands e oficina.events e bindings diagnostic.start.v1 / #.v1.
3. Cadastro REST de cliente sintetico com CPF valido, filial e veiculo vinculado.
4. Parada controlada do unico broker antes da abertura da OS: resposta RECEBIDA, Saga WAITING_DIAGNOSTIC e Outbox OS confirmada, mesmo com tentativa de publicacao falhando.
5. Restauracao do broker e processamento real de diagnostic.start.v1 pelo Execution.
6. Um diagnostico IN_PROGRESS no MongoDB, Inbox PROCESSED, historico e Outbox started.
7. diagnostic.started.v1 consumido pelo OS Service, levando a OS para EM_DIAGNOSTICO.
8. Republicacao do envelope original diagnostic.start.v1 com o mesmo eventId. O script aguarda um novo ACK da fila principal antes de comparar snapshots e confirmar ausencia de novo diagnostico, historico ou started.
9. Inclusao de item tecnico exclusivamente via REST, com quantidade="2.500" e precoPrevisto="150.000100".
10. Segunda parada do broker, seguida de conclusao REST: diagnostico COMPLETED, historico e Outbox preservados. A Saga continua WAITING_DIAGNOSTIC enquanto o evento nao foi entregue.
11. Restauracao e entrega de diagnostic.completed.v1. O OS Service cria quote.create.v1 e muda a Saga para WAITING_QUOTE.
12. A OS permanece EM_DIAGNOSTICO: nao existe quote.created.v1, aprovacao automatica ou resposta simulada do Billing.
13. Republicacao do envelope original diagnostic.completed.v1 com o mesmo eventId, seguida de novo ACK do consumidor OS. OS, Saga, Inbox, Outbox e historico permanecem identicos e nao ha outro quote.create.v1.
14. Validacao dos oito campos de cada envelope, UUIDs, occurredAt ISO-8601, origem, correlacao, Saga, routing key, persistencia e todos os campos dos itens, incluindo tipos string dos decimais.
15. Estado de saude final e preservacao dos dados confirmados apos as duas indisponibilidades.

A espera utiliza polling de condicoes observaveis com deadline: readiness, registro persistido, estado da Saga, mensagem efetivamente publicada e incremento de ACKs. Nao ha sleeps cegos para declarar sucesso.

As metricas de ACK do RabbitMQ reiniciam quando o broker reinicia. O teste captura o contador da instancia atual antes de cada republicacao e aguarda seu incremento, sem pressupor um total acumulado entre as duas indisponibilidades.

## Observacao e consultas

Duas filas duraveis exclusivas do teste, integration.observe.commands e integration.observe.events, recebem copias das mensagens publicadas. A consulta RabbitMQ management usa ack_requeue_true; nao consome as filas de negocio.

A fila observadora de comandos tambem roteia quote.create.v1 para registrar o JSON realmente publicado e permitir publisher confirm. Ela nao processa o orcamento nem responde pelo Billing.

A idempotencia e testada por **republicacao dos envelopes capturados**, sem novos IDs ou alteracao do payload. Nao e necessario forjar o atributo de transporte redelivered; a deduplicacao deve depender do eventId.

As consultas PostgreSQL usam default_transaction_read_only=on. As consultas MongoDB usam somente find e hello. Nenhum estado e forcado pelo banco. Todos os registros de negocio sao criados/alterados pelas APIs e consumidores reais dos servicos.

## Evidencias

Cada execucao gera target/integration-65/AAAAMMDD-HHMMSS/ com:

- result.json: resultado, etapa de eventual falha, IDs, estados finais e checks.
- containers-initial.json, rabbit-exchanges.json e rabbit-bindings.json.
- wire-*.json: envelopes realmente observados e propriedades AMQP.
- os-outbox-during-outage.json e execution-outbox-during-outage.json.
- snapshots anteriores e posteriores a cada republicacao.
- postgres-final.json e mongo-final.json.
- startup.log, services.log e cleanup.log (quando o ambiente e encerrado).

O JSON do envelope original permanece no campo payload das evidencias wire; seu ocorridoAt e precisao decimal nao sao reconstruidos pelo teste.

## Limpeza manual segura

Se usar -KeepEnvironment:

    docker compose -p oficina-integration-65 -f integration/compose.yaml down --volumes --remove-orphans

Se utilizou outro sufixo, informar o mesmo -p. Esse comando referencia somente o projeto isolado. Nunca executar down --volumes nos Compose de desenvolvimento ou de outros projetos.

## Falhas

Em caso de falha, result.json registra failureStage e error; os logs e snapshots sao coletados antes da limpeza. Use -KeepEnvironment para investigar. Corrigir somente configuracao ou harness do teste dentro de integration/; uma eventual alteracao de codigo dos servicos exige diagnostico, explicacao e autorizacao previa.
