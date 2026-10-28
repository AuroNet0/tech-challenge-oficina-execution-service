# Mensageria do Execution Service — etapa 6.4

## Fila operacional 9.1.2

Os comandos diagnostic.start.v1 e repair.start.v1 agora registram PENDING; os eventos started sao gravados somente no inicio REST autorizado. Esta regra substitui referencias historicas a inicio automatico neste documento. Contratos, Inbox, Outbox, retry e DLQ permanecem os mesmos. Consulte [fila operacional](operational-queue-9.1.2.md) e adapte o E2E 8.3.5 para chamar /inicio antes de aguardar started.

## Referencias e compatibilidade

Referencias da branch homolog do OS Service, consultadas nesta implementacao:

- https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md
- https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md
- domain/DiagnosticStartEnvelope.java e DiagnosticStartPayload.java
- application/saga/CommandEnvelope.java, SagaEnvelope.java e SagaEventHandler.java
- infrastructure/messaging/OutboxPublisher.java, RabbitMessageBrokerAdapter.java e SagaEventConsumer.java

O Execution Service nao possui orquestrador de Saga nem acessa o banco do OS Service. O unico orquestrador continua no OS Service. As alteracoes REST e o processamento de comandos nunca publicam diretamente no broker.

## Envelope

Todos os campos sao obrigatorios: eventId (UUID), eventType, occurredAt (Instant ISO-8601), correlationId (UUID), sagaId (UUID), osId (UUID), source e payload (objeto JSON). Comandos aceitam somente source=os-service e eventType igual a routing key recebida. Source e validacao de contrato; autenticidade depende das permissoes do broker, nao do texto do campo.

O evento de resultado recebe eventId proprio e source=execution-service, preservando osId, sagaId e correlationId. Uma tentativa repetida da Outbox reutiliza o mesmo eventId e o mesmo envelope persistido. Em conclusoes REST, X-Correlation-Id e preservado quando informado; na ausencia dele, utiliza-se a correlacao do ultimo comando processado da OS. Agregados preparados isoladamente por testes recebem uma correlacao nova. Historico de conclusao e Outbox usam a mesma correlacao.

Payloads de comandos:

| Comando | Payload |
| --- | --- |
| diagnostic.start.v1 | clienteId, veiculoId, filialId (UUIDs obrigatorios, conforme factory do OS Service), observacoes opcional |
| repair.start.v1 | objeto vazio |
| repair.cancel.v1 | reason nao vazio |

Os identificadores de cliente, veiculo e filial sao validados no DTO de entrada, sem consultas externas e sem duplicar dados pessoais nas colecoes tecnicas.

Payloads de resultados:

| Evento | Payload |
| --- | --- |
| diagnostic.started.v1 | {} |
| diagnostic.completed.v1 | items com itemId, descricao, tipo, quantidade, unidadePreco e precoPrevisto |
| diagnostic.failed.v1 | reason |
| repair.started.v1 | {} |
| repair.completed.v1 | repairId real, UUID canonico do agregado persistido |
| repair.failed.v1 | reason |
| repair.cancelled.v1 | {} |
| repair.cancellation-failed.v1 | reason |

quantidade e precoPrevisto sao strings decimais produzidas com BigDecimal.toPlainString, sem arredondamento ou calculo de orcamento. Os eventos started e completed seguem os payloads aceitos pelo SagaEventHandler do OS Service. Falhas tecnicas tambem estao disponiveis como operacoes transacionais internas dos servicos de aplicacao, sem novos endpoints HTTP.

## Topologia RabbitMQ

Exchanges topic duraveis:

- oficina.commands: comandos do OS Service.
- oficina.events: resultados para o OS Service.
- execution.commands.retry: transferencia confirmada de falhas transitorias.
- execution.commands.dlx: transferencia confirmada de mensagens rejeitadas.

| Fila duravel | Binding em oficina.commands |
| --- | --- |
| execution.diagnostic.start | diagnostic.start.v1 |
| execution.repair.start | repair.start.v1 |
| execution.repair.cancel | repair.cancel.v1 |

Cada fila possui uma fila auxiliar com sufixo .retry, ligada a execution.commands.retry com a mesma routing key. Ela aguarda o TTL configurado e devolve a mensagem a oficina.commands. execution.commands.dlq recebe # de execution.commands.dlx.

As filas principais nao possuem argumentos de DLX: o OS Service ja declara execution.diagnostic.start sem argumentos. Adicionar argumentos divergentes provocaria PRECONDITION_FAILED. Por isso, a transferencia para retry e DLQ e explicita, persistente, mandatory e confirmada antes de confirmar a entrega original.

O consumidor usa ACK automatico do container somente apos o metodo retornar: o proxy transacional ja confirmou o commit antes desse retorno. Nao existe ACK antecipado. Se a transferencia para retry/DLQ nao for confirmada, a entrega original permanece reentregavel. Indisponibilidade da infraestrutura de recuperacao exige restaurar a topologia/conexao, sem descartar mensagens.

## Transactional Inbox

inbox_messages armazena eventId como _id (indice unico nativo), eventType, sagaId, osId, correlationId, envelope original em payload, status, receivedAt, processedAt e lastError. Ha indice osId + receivedAt para resolver correlacao.

Fluxo transacional:

1. Consultar um eventId ja finalizado permite ignorar a reentrega.
2. Inserir RECEIVED com _id unico dentro da transacao.
3. Validar o estado e aplicar as operacoes do dominio.
4. Salvar agregado, historico e Outbox.
5. Finalizar Inbox como PROCESSED e confirmar a transacao.

O indice unico e a transacao protegem contra dois consumidores que nao encontraram o registro ao mesmo tempo. O perdedor sofre conflito e tenta novamente por entrega controlada; ao ver PROCESSED nao cria outro agregado, historico ou evento.

Comandos semanticamente rejeitados ficam REJECTED e seguem para DLQ. Falhas operacionais de dominio podem gerar o resultado failed correspondente na mesma transacao; sagaId inconsistente nao produz um evento contra outra Saga. JSON/envelopes malformados seguem para DLQ sem criar uma Inbox com identificadores nao confiaveis. Falhas de infraestrutura revertem tudo e sao reenviadas via fila .retry.

Uma nova mensagem diagnostic.start para a mesma OS e mesma Saga nao recria o diagnostico nem reemite started. repair.start ja IN_PROGRESS ou COMPLETED tambem nao reautoriza nem repete started. A mesma chave eventId com identificadores divergentes e rejeitada.

## Transactional Outbox

outbox_events armazena eventId como _id, aggregateId, aggregateType, eventType, envelope completo em payload, status, sagaId, osId, correlationId, createdAt, publishedAt, attempts, nextAttemptAt, lastError, lockedUntil e leaseToken.

Indices:

- status + nextAttemptAt: selecao de pendentes.
- status + lockedUntil: recuperacao de workers interrompidos.
- osId + sequence unico: ordem persistente por OS e protecao contra alocacao concorrente da sequencia.

sequence e um detalhe do documento MongoDB. E alocada com a insercao transacional; conflitos de alocacao nao sao ignorados. Antes de adquirir, seleciona-se somente o primeiro evento ainda nao publicado de cada OS. Um evento FAILED bloqueia os eventos seguintes daquela OS, permitindo intervencao sem enviar completed antes de started. Outras OS continuam sendo publicadas.

Aquisicao usa findAndModify atomico, incrementa attempts e gera um leaseToken novo. O worker publica fora de transacao MongoDB, com mensagem persistente, routing key=eventType, confirm correlacionado por tentativa e mandatory. ACK do broker com basic.return nao e sucesso: mensagem sem fila roteavel continua pendente para retry.

Atualizacoes de sucesso/falha exigem status PROCESSING, o token adquirido e lockedUntil maior que o instante atual. Um worker expirado nao pode marcar o evento como publicado ou reagendar a tentativa de outro worker.

Backoff de publicacao: min(initialBackoff * 2^(attempts-1), maxBackoff). Apos maxAttempts, o evento fica FAILED. Um processo interrompido deixa o evento PROCESSING: outro worker o recupera ao expirar o lease. Expiracao da ultima tentativa tambem finaliza como FAILED. Configure lease-duration confortavelmente maior que confirm-timeout e connection-timeout.

A entrega e at-least-once: uma queda depois do confirm e antes de salvar PUBLISHED pode publicar novamente o mesmo eventId. O OS Service possui Inbox para deduplicar. Nao existe garantia de exactly-once.

## Conclusoes REST

DiagnosticService.complete e RepairService.complete persistem agregado, historico e Outbox na mesma transacao MongoDB. Falha na Outbox reverte a conclusao. Chamadas repetidas ou concorrentes incompatíveis retornam HTTP 409 sem criar outro evento.

A API HTTP exige JWT RS256, MECANICO e decisao especifica da OS; na etapa 8.3.3 o canal produtivo de autorizacao permanece bloqueado ate integracao confiavel com OS. Isso nao exige JWT humano nos consumidores RabbitMQ. Nao existem endpoints de inicio, autorizacao manual, simulacao de eventos ou alteracao generica de status. A autorizacao de reparo continua sendo a operacao de dominio chamada pelo consumidor de repair.start.v1 apos diagnostico concluido e consistente. Detalhes: [autorizacao 8.3.3](security/execution-authorization-8.3.3.md).

## Compensacao de reparos

| Estado atual | Tratamento de repair.cancel.v1 |
| --- | --- |
| PENDING | cancel() confirma CANCELLED; historico e repair.cancelled.v1 |
| CANCELLED | confirma cancelamento ja efetivado; repair.cancelled.v1 |
| IN_PROGRESS | preserva estado fisico; repair.cancellation-failed.v1 com reason, exige intervencao |
| FAILED | preserva a falha e o trabalho fisico; repair.cancellation-failed.v1 com reason |
| COMPLETED | preserva a conclusao; repair.cancellation-failed.v1 com reason |
| Inexistente | nao confirma um cancelamento ficticio; resultado cancellation-failed e Inbox rejeitada |

Nenhum pedido de compensacao desfaz trabalho fisico automaticamente. O dominio de diagnostico tambem preserva FAILED; nao o transforma implicitamente em CANCELLED.

## Retry, DLQ e operacao segura

Comandos transitorios usam execution-attempt e filas .retry com atraso fixo. COMMAND_MAX_DELIVERIES limita o numero de processamentos, incluindo o inicial. Apos o limite, a mensagem segue para DLQ. Erros permanentes vao diretamente para DLQ, sem passar por retries de negocio.

Nao ha binding da DLQ de volta para comandos: nao existe ciclo automatico infinito de mensagens invalidas. Para reprocessar, um operador deve inspecionar o motivo, corrigir a causa e republicar no binding original. Uma Inbox PROCESSED continua idempotente com o mesmo eventId. Uma Inbox REJECTED nao deve ser apagada: para uma correcao semantica aprovada, use um novo eventId e preserve correlacao/Saga/OS. Falhas transitorias com transacao abortada nao deixaram Inbox e podem reutilizar o eventId.

FAILED na Outbox e um estado operacional inspecionavel. Nao ha endpoint automatico de redrive: deve haver procedimento administrativo auditado para reabilitar o mesmo evento, preservando eventId e a ordem da OS. Nao apagar/recriar envelopes para esconder falhas.

As filas principais e de retry sao classic duraveis. O encaminhamento TTL do broker pressupoe exchanges/filas de destino disponiveis; para requisitos de HA e dead lettering at-least-once sob falhas de cluster, provisionar filas quorum/politicas apropriadas antes da producao. Isso deve ser coordenado com a topologia compartilhada do OS Service.

## Configuracao e execucao local

MongoDB continua em replica set rs0. A aplicacao inicia mesmo se RabbitMQ estiver temporariamente indisponivel: o listener tenta reconectar, e a Outbox continua persistida (eventos podem atingir FAILED se a indisponibilidade exceder o limite). Rabbit nao faz parte do readiness do bootstrap; monitorar filas, lastError e eventos FAILED separadamente.

Broker proprio opcional, sem colidir com 5672/15672 do OS Service:

```bash
docker compose --profile local-broker up --build -d
```

O broker usa 5673 (AMQP) e 15673 (management), sem container_name fixo. A aplicacao usa host.docker.internal:5673 no Compose; o perfil pode ser omitido ao usar broker externo. Para rodar a aplicacao no host com esse broker, definir RABBITMQ_PORT=5673.

Variaveis principais:

| Variavel | Padrao |
| --- | --- |
| RABBITMQ_HOST / RABBITMQ_PORT | localhost / 5672 no host; host.docker.internal / 5673 no Compose |
| RABBITMQ_USERNAME / RABBITMQ_PASSWORD | execution / execution_local, somente desenvolvimento |
| RABBITMQ_VHOST | / |
| RABBITMQ_CONFIRM_TIMEOUT | 5s |
| COMMAND_CONSUMER_ENABLED | true |
| COMMAND_RETRY_DELAY / COMMAND_MAX_DELIVERIES | 5s / 5 |
| OUTBOX_PUBLISHER_ENABLED / OUTBOX_FIXED_DELAY | true / 5s |
| OUTBOX_BATCH_SIZE / OUTBOX_LEASE_DURATION | 10 / 1m |
| OUTBOX_MAX_ATTEMPTS | 5 |
| OUTBOX_INITIAL_BACKOFF / OUTBOX_MAX_BACKOFF | 10s / 5m |

Para compartilhar o broker do Compose do OS Service:

1. Inicie o broker do OS Service normalmente; nao habilite local-broker neste projeto.
2. Configure RABBITMQ_HOST=host.docker.internal e RABBITMQ_PORT=5672 no Execution.
3. Configure credenciais e RABBITMQ_VHOST que tenham acesso ao mesmo broker/vhost do OS Service.
4. Execute docker compose up --build -d neste projeto.

Alternativamente, em uma rede Docker compartilhada, configure RABBITMQ_HOST com o nome/alias acessivel do broker e RABBITMQ_PORT=5672. A conexao de rede deve ser configurada explicitamente pelo operador. Apenas publicar os mesmos nomes de exchanges em brokers diferentes nao integra os servicos. Nao foi alterado o Compose do OS Service.

Se os dois servicos usarem o broker opcional do Execution, o OS Service deve apontar para host.docker.internal:5673 com as mesmas credenciais/vhost, usando sua configuracao externa. Nao se deve publicar comandos ficticios permanentes para criar agregados.

Credenciais default sao exclusivas de desenvolvimento e nao devem ser reutilizadas. Em producao, use usuarios de servico e permissoes restritas no vhost compartilhado: consumo apenas das filas de comandos, publicacao apenas de eventos/retry/DLQ. Provisione a topologia com uma identidade administrativa e restrinja configure da identidade de execucao (desabilitando declaracoes dinamicas quando provisionado externamente). O produtor de comandos deve ser restrito ao OS Service. Nao usar guest ou logar senhas e corpos completos.

## Testes e evidencias

./mvnw clean verify executa testes de dominio, aplicacao, HTTP e mensageria com MongoDB 8 replica set e RabbitMQ 4 em Testcontainers. Nao depende de bancos ou brokers instalados externamente.

Os testes verificam a topologia compativel com a fila declarada pelo OS Service, consumo real, entrega confirmada para fila com o binding #.v1 usado pelo consumidor da Saga, envelopes e payloads tipados, decimais, correlacoes, deduplicacao, concorrencia de consumidores/publishers, recuperacao e protecao de lease, mensagens unroutable, falha de conexao, retries, DLQ e rollback das conclusoes REST/Outbox.

A suite valida comunicacao real pelo broker e os contratos consumidos pelo OS Service; nao substitui uma Saga completa com OS/Billing/pagamentos em execucao. Relatorio JaCoCo: target/site/jacoco/index.html. Os testes HTTP anteriores permanecem ativos e autenticados.

Validacao realizada em 2026-10-19: ./mvnw clean verify (mvnw.cmd no Windows) aprovado, 119 testes sem falhas ou skips, incluindo 25 cenarios de integracao de mensageria. JaCoCo: 718 de 735 linhas, 97,69%, sem novas exclusoes. O Compose com local-broker foi reconstruido e ficou saudavel; a aplicacao tambem reiniciou e retornou readiness UP com o broker parado, antes de sua restauracao. Logs da verificacao ficam em target/verification-6.4.log e target/compose-6.4.log.
