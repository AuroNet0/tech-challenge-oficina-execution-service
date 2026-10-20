# Validacao apos correcao ISO-8601 do OS Service

Execucao real em 20/10/2026, branches homolog, sem commit ou push.

## Causa raiz e correcao

Os serializers de mensagens usam Jackson 2.21.5 (`com.fasterxml.jackson`), embora Jackson 3 tambem esteja no classpath. O ObjectMapper construido manualmente registra JavaTimeModule, mas mantem WRITE_DATES_AS_TIMESTAMPS habilitado; por isso Instant era emitido como numero.

No OS Service, SagaMessageSerializer e DiagnosticStartMessageSerializer agora usam ObjectWriter localizado com WRITE_DATES_AS_TIMESTAMPS desabilitado. O mapper compartilhado, a configuracao REST, os demais campos do envelope e o contrato do Execution nao foram alterados. docs/events.md registra a decisao.

## Testes

- DiagnosticStartMessageSerializerTest verifica occurredAt textual, parse ISO-8601, instante original e ausencia de mutacao do mapper compartilhado.
- Novo SagaMessageSerializerTest verifica os cinco comandos: diagnostic.start.v1, quote.create.v1, repair.start.v1, repair.cancel.v1 e payment.request.v1, preservando envelope e payload.
- OS Service: mvnw.cmd clean verify concluido com BUILD SUCCESS; 69 testes, nenhuma falha, erro ou teste ignorado. JaCoCo: 87,77% de linhas (1134 cobertas, 158 nao cobertas).

## Ambiente e fluxo integrado

Projeto Compose isolado oficina-integration-65: OS Service, PostgreSQL 17, Execution Service, MongoDB 8 replica set rs0 e um unico RabbitMQ 4 compartilhado. Todos os cinco containers estavam healthy; MongoDB PRIMARY, exchanges e bindings foram verificados.

Resultado final: **115 verificacoes aprovadas**. Cliente, filial, veiculo e OS foram criados pelas APIs. O comando diagnostic.start.v1 foi publicado e consumido; o Diagnostic real foi persistido; diagnostic.started.v1 levou a OS a EM_DIAGNOSTICO. Um item foi adicionado por REST e o diagnostico concluido. diagnostic.completed.v1 foi consumido pelo OS, que produziu quote.create.v1 e avancou a Saga para WAITING_QUOTE.

| Identificador | Valor |
| --- | --- |
| OS | 07db4892-3da6-48d4-b939-4286ef7435b4 |
| Saga | d2c241b8-a41f-48e7-92e1-5ee0f842a449 |
| Diagnostic | bca797a7-0cb4-47cf-9357-1abd547b8582 |
| Correlation | 5f4465a8-0263-484d-b839-22698b333da6 |
| diagnostic.start.v1 | acda1799-82aa-4464-b0d4-40276b99cf75 |
| diagnostic.started.v1 | 2800df6c-9857-42f8-aa98-19e68ef0b0d2 |
| diagnostic.completed.v1 | 7dbacfb7-bf7d-4b23-b8b2-43a7ec704dae |
| quote.create.v1 | c607cb37-3d29-40bd-9bc1-8f4f6873ca71 |

O occurredAt real de diagnostic.start.v1 foi "2026-10-20T15:25:29.581254404Z". Todos os campos do envelope foram inspecionados; quantidade "2.500" e precoPrevisto "150.000100" foram preservados como strings decimais nos eventos.

Estados finais: OS EM_DIAGNOSTICO, Saga WAITING_QUOTE e Diagnostic COMPLETED. Nenhum Billing simulado, aprovacao automatica ou escrita direta de estado no banco foi utilizado.

## Idempotencia e recuperacao

- Reenvio do diagnostic.start.v1 original, com mesmo eventId: um unico Diagnostic; Inbox, historico e Outbox inalterados; nenhum started adicional.
- Reenvio do diagnostic.completed.v1 original: Saga, historico e Inbox inalterados; nenhum quote.create.v1 adicional.
- Broker interrompido antes da abertura da OS: OS e Outbox preservadas; publisher recuperou e entregou o comando apos restauracao.
- Broker interrompido antes da conclusao REST: Diagnostic, historico e Outbox preservados; publisher recuperou e entregou a conclusao apos restauracao.
- DLQ vazia na execucao final. Somente volumes novos e mensagens geradas apos a correcao foram usados; nenhum evento antigo da DLQ foi reutilizado.

## Problemas e evidencias

Nenhuma nova incompatibilidade de contrato foi encontrada. A primeira reexecucao encontrou apenas uma falha de instrumentacao: o harness esperava um contador cumulativo de ACK, mas RabbitMQ reinicia esse contador apos restart. O runner foi corrigido para usar a linha de base da instancia atual e o fluxo inteiro foi repetido com volumes novos; nenhuma regra de negocio foi alterada.

Evidencias em target/integration-65/20261020-122445/: result.json, JSONs dos envelopes capturados, snapshots PostgreSQL/MongoDB, logs dos servicos, runner.log e os-clean-verify.log. O ambiente isolado foi removido automaticamente ao final, incluindo apenas seus proprios volumes; cleanup.log registra a limpeza.

Reproducao: powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/run.ps1. Consulte integration/README.md para requisitos e opcoes. O relatorio REPORT.md preserva a primeira tentativa anterior a correcao, nao o resultado atual.
