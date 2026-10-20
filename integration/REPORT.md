# Relatorio da etapa 6.5 — 2026-10-20

## Primeira tentativa, anterior a correcao autorizada

O ambiente isolado iniciou corretamente com cinco containers healthy e um unico RabbitMQ compartilhado. O fluxo foi interrompido na entrega de diagnostic.start.v1: o OS Service serializa occurredAt como numero, enquanto docs/events.md documenta uma string ISO-8601 e o Execution valida esse formato.

Na tentativa original, nenhum codigo Java, application.yml ou Compose original foi alterado. A correcao foi posteriormente autorizada pelo usuario e implementada de forma localizada nos dois serializers do OS, usando ObjectWriter sem timestamps numericos. O mapper compartilhado e as APIs REST permanecem com a mesma configuracao. O arquivo proposed-os-jackson.patch registra apenas a proposta global inicial, que nao foi aplicada; a implementacao final prefere writers locais.

## Baseline

Ambos os repositorios estavam limpos na branch homolog antes da criacao do harness.

| Servico | Commit | clean verify | JaCoCo por linhas |
| --- | --- | --- | --- |
| Execution | cef6bfbd80368675c0abf4bb864e399cbf5cb639 | 119 testes, zero falhas/erros/skips | 97,69% |
| OS | b4c98bc08d573534dc680ab26ad5edfcba9fbb38 | 64 testes, zero falhas/erros/skips | 87,76% |

## Ambiente

Projeto Compose exclusivo: oficina-integration-65. Containers:

- oficina-integration-65-postgres-1: PostgreSQL 17, healthy.
- oficina-integration-65-mongodb-1: MongoDB 8, replica set rs0, PRIMARY e healthy.
- oficina-integration-65-rabbitmq-1: RabbitMQ 4 management, healthy.
- oficina-integration-65-os-service-1: OS Service, healthy, porta local 18080.
- oficina-integration-65-execution-service-1: Execution Service, healthy, porta local 18081.

Management RabbitMQ: 127.0.0.1:18572. AMQP apenas na rede interna. Volumes e rede exclusivos do projeto; nenhum volume de desenvolvimento/producao foi reutilizado.

Depois da coleta, o ambiente isolado foi encerrado e seus volumes de teste removidos pelo projeto/label exclusivo oficina-integration-65. Os arquivos de evidencias permanecem disponiveis. Os ambientes de desenvolvimento nao foram encerrados ou alterados.

oficina.commands e oficina.events foram encontrados como exchanges topic duraveis. Os bindings diagnostic.start.v1 -> execution.diagnostic.start e #.v1 -> os.saga.events foram confirmados. Filas observadoras exclusivas capturaram os envelopes reais sem consumir as filas de negocio.

## Fluxo efetivamente executado

1. Cadastro de cliente sintetico, filial e veiculo vinculado pelas APIs reais do OS.
2. Parada controlada do unico RabbitMQ.
3. Abertura da OS pela API: resposta RECEBIDA e Saga WAITING_DIAGNOSTIC.
4. Confirmacao PostgreSQL read-only de OS, historico inicial, Saga e Outbox diagnostic.start.v1 persistidos mesmo com falha de publicacao.
5. Restauracao do RabbitMQ: o publisher do OS recuperou e marcou a mensagem como PUBLISHED.
6. Execution recebeu a mensagem e a transferiu para execution.commands.dlq como rejeitada.
7. Consulta da API de diagnostico retornou 404; nenhum diagnostico, Inbox, historico tecnico ou Outbox foi criado.

IDs desta tentativa:

- OS: d1a32f28-bbc8-4a07-b029-6c318132c000
- Saga: f50879bb-59d9-4c8b-98b2-46a0af2a1079
- correlationId: 0bae5290-63c9-42e7-b2f8-89bfdbed9f5f
- diagnostic.start.v1 eventId: 920676e6-cc09-498a-a5f0-3d0960d4bea2

## Contrato e diagnostico

Trecho do JSON efetivamente publicado e preservado na DLQ:

```json
{
  "eventId": "920676e6-cc09-498a-a5f0-3d0960d4bea2",
  "eventType": "diagnostic.start.v1",
  "occurredAt": 1792508696.426768991,
  "correlationId": "0bae5290-63c9-42e7-b2f8-89bfdbed9f5f",
  "sagaId": "f50879bb-59d9-4c8b-98b2-46a0af2a1079",
  "osId": "d1a32f28-bbc8-4a07-b029-6c318132c000",
  "source": "os-service"
}
```

O payload completo, com clienteId, veiculoId, filialId e observacoes, esta nas evidencias wire. Os outros identificadores, source, routing key e propriedades persistentes estavam corretos. A divergencia e o tipo/formato de occurredAt.

Origem: OS Service, infrastructure/config/JacksonConfig.java cria new ObjectMapper().findAndRegisterModules() sem desabilitar SerializationFeature.WRITE_DATES_AS_TIMESTAMPS. O serializer JavaTime de Jackson 2 emite Instant numerico por padrao nesse mapper manual.

Destino: Execution, infrastructure/messaging/MessageCodec.java exige texto em occurredAt e aplica Instant.parse. A rejeicao acontece antes da transacao da Inbox, portanto a ausencia de registro de Inbox neste caso e esperada.

Log relevante do Execution:

```text
command eventId=null eventType=null sagaId=null osId=null correlationId=null result=rejected
command forwarding messageId=920676e6-cc09-498a-a5f0-3d0960d4bea2 eventType=diagnostic.start.v1 attempt=1 result=dlq
```

O publisher do OS ter recebido confirm e marcado PUBLISHED comprova entrega ao broker, nao sucesso no processamento pelo Execution.

## Estados e limites da validacao

| Verificacao | Resultado |
| --- | --- |
| OS inicial RECEBIDA | Confirmado |
| Saga inicial WAITING_DIAGNOSTIC | Confirmado |
| Outbox OS preservada durante indisponibilidade | Confirmado |
| Publisher OS recupera apos restauracao | Confirmado; PUBLISHED, attempts=3 falhas registradas |
| Diagnostico criado/iniciado | Falhou por contrato; zero documentos |
| diagnostic.started.v1 e OS EM_DIAGNOSTICO | Nao alcancado |
| Item tecnico e conclusao REST integrada | Nao executados nesta tentativa |
| diagnostic.completed.v1 e quote.create.v1 | Nao alcancados |
| Saga WAITING_QUOTE | Nao alcancada; permanece WAITING_DIAGNOSTIC |
| Idempotencia integrada de start/completed | Nao executada apos a falha |
| Recuperacao da Outbox Execution neste ambiente | Nao executada apos a falha |

Nao foram enviados quote.created.v1, aprovacao de orcamento ou respostas simuladas do Billing. Nenhum estado foi alterado diretamente nos bancos. Nao se declara sucesso do fluxo integrado com base apenas nas suites individuais.

## Correcao minima proposta

Desabilitar WRITE_DATES_AS_TIMESTAMPS no mapper Jackson 2 ja existente do OS, sem alterar nomes, campos ou consumidor do Execution. Adicionar teste de regressao usando esse mapper real com os dois tipos de envelope publicados pelo OS. Reexecutar a suite OS, reconstruir a imagem e executar o roteiro em dados isolados novos.

Nao basta configurar uma propriedade Spring Jackson na inicializacao se o mapper continuar sendo construido manualmente, sem aplicar as configuracoes do builder. Tambem nao seria correto mascarar o problema transformando a mensagem no harness antes de entrega-la ao Execution.

## Evidencias reproduziveis

Arquivos desta tentativa em target/integration-65/20261020-120448/:

- result.json: resultado, etapa e erro original.
- containers-initial.json e startup.log.
- rabbit-exchanges.json e rabbit-bindings.json.
- os-outbox-during-outage.json, postgres-final.json e mongo-final.json.
- integration.observe.commands-captured.json: JSON original publicado.
- execution.commands.dlq-captured.json: mesmo envelope rejeitado, com execution-attempt=1.
- services.log: logs reais de ambos os servicos.

Consulte README.md desta pasta para comandos, reexecucao, parametros e limpeza exclusiva do ambiente isolado. O harness foi ajustado para registrar e validar o envelope antes de aguardar um diagnostico, identificando imediatamente esse tipo de divergencia nas proximas execucoes.

Logs das suites individuais anteriores a correcao: target/integration-65/verification-execution.log e verification-os.log. A proposta inicial passou por git apply --check sem ser aplicada naquela etapa. Este arquivo preserva o diagnostico historico e nao representa o resultado da reexecucao apos a correcao autorizada. Consulte [REPORT-ISO-FIX.md](REPORT-ISO-FIX.md) para a correcao aplicada e o fluxo integrado aprovado.
