# Execution Service — autenticação e autorização 8.3.3

Data: 26/10/2026. Escopo exclusivo do Execution; etapa 8.3.4 não iniciada.

## 1. Referências, Git e versões

Inspecionados antes da implementação:

- Execution: `SecurityConfig`, os quatro controllers (incluindo bootstrap), os três serviços, portas de entrada, `CommandHandler`, `CommandConsumer`, `MessageCodec`, `RabbitMessagingConfig`, Inbox/Outbox, configuração/transações MongoDB, testes de domínio/aplicação/HTTP/persistência/mensageria e `docs/messaging.md`.
- OS: `docs/security/os-authorization-8.3.2-b.md`, `ResourceAuthorization`, `OperationalAccessPort`, `OperationalAccessAdapter`, `Capability` e migração V5 com atribuições persistidas e revogáveis.
- Auth: `docs/authentication.md`, `docs/security/authorization-8.3.1.md`, formato dos tokens, JWKS e política de rotação.

| Repositório local consultado | Branch | HEAD analisado | Estado inicial |
| --- | --- | --- | --- |
| Execution | homolog | c1769c390825d12f35841e1b0d8f9508afa926a1 | limpo |
| OS | homolog | 8d551aacee92fdf71448b271c2ae9e2712830693 | limpo |
| Auth | homolog | 0f26aa8ee36230ee9dbd3ff0bb244da62c347503 | limpo |

Spring Boot real: **4.1.1**; Java **21**. Resource Server vem de `spring-boot-starter-oauth2-resource-server`, com versões Spring Security/Nimbus gerenciadas pelo parent existente. Não foi feito downgrade ou mudança de versão do projeto. Git local por caminho absoluto; safe.directory somente por comando de leitura nos repositórios adjacentes, sem alteração global.

## 2. Arquitetura JWT e configuração JWKS

HTTP Bearer → Resource Server → `AuthJwtDecoder` → conversão explícita de roles → allowlist método/rota → `@PreAuthorize` no adapter HTTP → resolução local do osId → `TechnicalAuthorizationPort` → decisão vinculada ao usuário, OS, capacidade e correlação → caso de uso/transação existente.

Variáveis obrigatórias (sem padrões privilegiados):

| Variável | Finalidade |
| --- | --- |
| EXECUTION_JWT_ISSUER | issuer exato do Auth por ambiente |
| EXECUTION_JWT_AUDIENCE | audience autorizada por ambiente |
| EXECUTION_JWT_JWKS_URI | endpoint HTTPS confiável do JWKS |

Sem configuração válida a aplicação falha na inicialização. A montagem do decoder não consulta o Auth: o JWKS é buscado quando necessário. HTTPS obrigatório, validação TLS normal da JVM, sem redirects de JWKS; connect/read timeout de **2 segundos** cada. Não há trust-all, segredo privado Auth, consulta ao banco Auth ou introspecção por requisição.

O decoder configura explicitamente um `CaffeineCache` do Spring com **TTL de 60 segundos após escrita**, uma entrada por instância e sem renovação por leitura, alinhado ao `max-age=60` do Auth. Esse cache é passado a `NimbusJwtDecoder.cache(...)`; no Spring Security 7.1.1 isso desabilita a camada padrão de cache Nimbus, evitando acumular os antigos cinco minutos. Não há cache próprio nem cache HTTP adicional no cliente configurado. kid novo continua provocando busca/refresh. A chave conhecida pode ser usada durante indisponibilidade somente enquanto o cache estiver válido; cache expirado ou cold start sem JWKS válido falham fechados. Testes com `Ticker` controlado comprovam leitura aos 59s, expiração aos 60s, substituição/retirada de chave e falha com cache expirado sem sleeps; o teste existente de rotação com duas chaves permanece preservado. O cache contém material público, nunca atribuições/decisões positivas. A retirada não é revogação instantânea: a chave pode permanecer aceita durante a janela de até 60s do cache local. Coordenar publicação/rotação e eventual cache intermediário com o Auth antes do deploy.

## 3. Claims validadas

Validação de estrutura seguida de verificação criptográfica RS256 pelo Nimbus e validação temporal:

- Header: alg exclusivamente RS256, typ JWT, kid não vazio e seleção de exatamente uma chave pública; kid ambíguo é recusado. Campos fora de alg/typ/kid são rejeitados (inclusive referências a chaves fornecidas pelo token).
- iss exato; aud como lista não vazia de strings contendo a audiência configurada.
- sub e jti UUID canônicos; não usar CPF, email ou identificador de cliente como identidade.
- iat, nbf, exp obrigatórios e inteiros; iat não negativo/não futuro, nbf igual a iat conforme emissor existente; exp maior que iat e que o relógio atual; TTL máximo 900 segundos.
- Clock skew fixo **zero** (dentro do limite máximo 60 segundos do contrato Auth); Clock injetável no decoder. Requer relógios sincronizados.
- roles obrigatoriamente lista, sem duplicatas, somente CLIENTE/ATENDENTE/MECANICO/GERENTE; roles vazias autenticam sem privilégios e recebem 403 nas operações.

JWT sem assinatura, none, HS256, RS384, kid ausente/desconhecido, assinatura incorreta, claims ausentes/malformadas, expiração, nbf/iat futuros ou duração excessiva são recusados. Parse de claims nunca concede acesso sem assinatura válida. jti não implica blacklist, introspecção ou revogação distribuída.

## 4. Papéis e sessão

CLIENTE → ROLE_CLIENTE; ATENDENTE → ROLE_ATENDENTE; MECANICO → ROLE_MECANICO; GERENTE → ROLE_GERENTE. Não há hierarquia automática. Ter vários papéis inclui somente as authorities explicitamente emitidas.

Basic e autenticação por formulário não estão habilitados. HTTP é stateless, sem request cache/sessão de login; CSRF desabilitado no canal Bearer sem credenciais de navegador aceitas automaticamente. Serviços de aplicação não dependem de SecurityContext.

CLIENTE acompanha o serviço no OS, sem DTO técnico completo. ATENDENTE consulta o resumo operacional no OS, conforme filial. GERENTE não recebe leitura global nem escrita técnica; futura leitura gerencial exige capacidade e escopo próprios.

## 5. Matriz de endpoints

Prefixo técnico: `/api/v1`. Todas as linhas técnicas exigem JWT válido e ROLE_MECANICO, além da decisão específica atual.

| Método e rota | Alvo da decisão | Capacidade proposta | Runtime atual |
| --- | --- | --- | --- |
| GET /diagnosticos/{id} | osId do diagnóstico persistido | DIAGNOSTIC_READ | bloqueado |
| GET /diagnosticos?osId= | OS solicitada | DIAGNOSTIC_READ | bloqueado |
| POST /diagnosticos/{id}/itens | osId do diagnóstico persistido | DIAGNOSTIC_WRITE | bloqueado |
| PATCH /diagnosticos/{id}/observacoes | osId do diagnóstico persistido | DIAGNOSTIC_WRITE | bloqueado |
| POST /diagnosticos/{id}/conclusao | osId do diagnóstico persistido | DIAGNOSTIC_COMPLETE | bloqueado |
| GET /reparos/{id} | osId do reparo persistido | REPAIR_READ | bloqueado |
| GET /reparos?osId= | OS solicitada | REPAIR_READ | bloqueado |
| PATCH /reparos/{id}/observacoes | osId do reparo persistido | REPAIR_WRITE | bloqueado |
| POST /reparos/{id}/conclusao | osId do reparo persistido | REPAIR_COMPLETE | bloqueado |
| GET /execucoes/historico?osId= | OS solicitada | HISTORY_READ | bloqueado |
| GET /actuator/health, /actuator/health/liveness, /actuator/health/readiness | probe | sem JWT humano | disponível, sem detalhes/componentes |
| /internal/bootstrap | técnico | nenhuma concedida | negado a todos |
| Swagger, OpenAPI, demais Actuator e demais métodos/rotas | técnico/desconhecido | nenhuma concedida | deny-by-default |

Swagger/OpenAPI desabilitados; mesmo habilitar a geração não muda a política de acesso. Probes GET devem ficar em rede interna permitida pela infraestrutura; não incluir tokens humanos nas probes. Health não retorna componentes Mongo/Rabbit. Não foi aberto endpoint administrativo/técnico alternativo.

401: JWT ausente/inválido; 403: papel/decisão insuficiente, decisão de outra OS/capacidade/usuário/correlação, ou rota não autorizada. 503 sanitizado: dependência ausente/indisponível. 404 pode ocorrer quando o agregado não existe ao resolver osId; não se promete ocultação temporal de existência de UUID. Domínio mantém seus 409 e validação HTTP seus 400 após os controles cabíveis.

## 6. Autorização por OS e preservação do domínio

`TechnicalAuthorization` protege cada método do controller e exige `JwtAuthenticationToken` autenticado e ROLE_MECANICO. Não aceita usuário Basic/mock genérico como identidade produtiva. Por ID usa o agregado carregado do MongoDB; osId enviado como query adicional nunca substitui esse valor. Consultas por OS e histórico autorizam essa OS antes de retornar dados.

Uma decisão positiva só vale se usuário, OS, capacidade e UUID de correlação gerado pelo Execution coincidirem integralmente com a requisição. Uma leitura não autoriza escrita nem conclusão. Não há cache de grants/decisões, fallback, cópia de atribuições ou acesso PostgreSQL do OS.

Autorização ocorre antes de invocar mutações e abrir a transação de negócio. `Diagnostic`, `Repair`, `ExecutionHistory`, regras de estados, histórico, controle otimista, transações, Inbox/Outbox, retry e DLQ não foram alterados. A resolução prévia lê o agregado; o caso de uso continua responsável pela transação e pelo controle de versão. osId é imutável nos fluxos existentes. Há testes de negação sem mudanças em agregado, histórico ou Outbox, e testes existentes de concorrência/rollback preservados.

Revogação concorrente após decisão e antes do commit não tem atomicidade entre serviços. A próxima operação consulta novamente a fonte. Exigência mais forte dependerá de protocolo distribuído aprovado; nenhum lease ou snapshot local de autorização foi inventado.

## 7. Contrato interno necessário com OS

**Não há endpoint interno adequado no OS analisado.** Seus GETs `/operacional` e `/veiculo-operacional` são entradas de usuário para DTOs resumidos, não decisões internas de capacidade autenticadas por serviço.

Além disso, `mechanic_assignments` admite hoje apenas **OPERATIONAL_READ e VEHICLE_OPERATIONAL_READ**. Essas concessões não autorizam escrita/conclusão no Execution. Nenhuma delas é convertida implicitamente em DIAGNOSTIC_WRITE ou REPAIR_COMPLETE.

`TechnicalAuthorizationPort` define contrato local/proposto, não afirma compatibilidade já implantada no OS. Capacidades granulares DIAGNOSTIC_READ/WRITE/COMPLETE, REPAIR_READ/WRITE/COMPLETE e HISTORY_READ são necessárias para separar objetos, escrita e conclusão; HISTORY_READ evita tratar histórico completo como resumo operacional. OS_READ não é usado como substituto global. A aprovação do mapeamento de leituras e a extensão explícita das capacidades de escrita/conclusão no OS são dependências futuras, com migração/provisionamento/auditoria próprios e autorização específica.

Esqueleto do contrato preparado, ainda não implementado no OS e insuficiente para ativação sem as evidências de identidade descritas abaixo:

```http
POST /internal/technical-authorization
Authorization: Bearer <credencial exclusiva do serviço Execution>
X-Correlation-Id: <uuid gerado pelo Execution>
Content-Type: application/json
```

```json
{
  "usuarioId": "<sub UUID validado pelo Execution>",
  "osId": "<UUID resolvido localmente>",
  "capability": "DIAGNOSTIC_WRITE",
  "correlationId": "<uuid>"
}
```

Resposta mínima: os mesmos quatro campos e `allowed: true|false`, sem pessoa, entidade ou detalhes da concessão. OS deve autenticar a identidade chamadora e conferir sua autorização para delegar essa verificação, validar o usuário representado no contrato aprovado e reler a atribuição/capacidade **ativa** persistida naquela OS. Identidade do serviço vem da credencial validada, nunca de header autodeclarado ou do body. Falhas/contratos inválidos não devem produzir grants. O UUID de correlação é novo por decisão, independente da correlação de negócio do frontend.

`TechnicalAuthorizationHttpAdapter` está **preparado, sem registro de bean nem configuração para ativação**. Exige HTTPS, timeout connect/read de 2s e `ServiceCredential` não vazia antes de qualquer chamada. Bearer técnico separado; atualmente não lê SecurityContext nem encaminha JWT humano. Não há implementação produtiva do emissor de credencial de serviço nesta etapa. O mecanismo de emissão, audience/scope/identidade de serviço e a delegação do usuário precisam ser aprovados e verificados no OS (ou implementar mTLS/IAM em adapter próprio aprovado). Não usar JWT humano como credencial técnica, segredo arbitrário ou resposta pública do OS para ativá-lo.

**Condições obrigatórias para ativação futura:** autenticação técnica confiável do Execution; transporte HTTPS do **JWT original da requisição HTTP autenticada**, além da credencial técnica; validação independente desse JWT pelo OS (assinatura, issuer, audience e claims); derivação da identidade exclusivamente de `sub`, removendo `usuarioId` autodeclarado ou exigindo igualdade; verificação da atribuição/capacidade **ativa persistida no OS**. Possuir somente credencial técnica não pode autorizar a escolha de qualquer usuário. O esqueleto atual não transporta essa prova do usuário e não deve ser ativado como está. JWT humano não será transportado em envelopes RabbitMQ. Não foram implementados protocolos criptográficos próprios ou mecanismos adicionais de replay nesta etapa.

O único adapter registrado em runtime é `UnavailableTechnicalAuthorizationAdapter`, que sempre lança indisponibilidade. Substituí-lo exige mudança deliberada e testes E2E reais; nenhuma flag local permite autorização positiva.

## 8. Limitações efetivas

- JWT/RBAC, resolução do alvo, validação da decisão e negação segura estão implementados.
- Toda leitura/escrita técnica dependente de atribuição permanece bloqueada em produção (503 para mecânico elegível e alvo resolvido); papéis insuficientes recebem 403 antes da dependência.
- Decisões positivas existem apenas em mocks de testes e não comprovam integração Execution↔OS.
- Não há E2E distribuído, API interna OS, provisionamento/revogação administrativa ou concessões técnicas de escrita no modelo atual OS. Não declarar autorização distribuída concluída.
- Nenhum Auth/OS/Billing foi alterado e nenhuma Saga completa foi simulada com alteração de estados de bancos.

## 9. Separação HTTP/RabbitMQ

`@PreAuthorize` existe somente nos controllers HTTP. `DiagnosticService`, `RepairService`, `ExecutionHistoryService` e `CommandHandler` não receberam autorização humana. Consumidores continuam validando source `os-service`, routing key/eventType, UUIDs, payload e Saga; source no envelope não é autenticação criptográfica do produtor.

`diagnostic.start.v1`, `repair.start.v1` e `repair.cancel.v1` continuam independentes de JWT humano; nenhum token foi acrescentado ao envelope. Testes Rabbit foram retirados do contexto `@WithMockUser`; somente as chamadas REST da suíte usam Bearer RS256 e decisão positiva de teste. Os testes de comandos comprovam processamento sem SecurityContext humano mesmo com a dependência HTTP de autorização indisponível.

Preservadas as transações MongoDB, deduplicação Inbox, ordem/lease/retry da Outbox, confirmações AMQP, retry e DLQ. A suíte real verifica diagnostic.started/completed, repair.started/completed/cancelled/cancellation-failed, decimais, rollback, reentregas, concorrência, retries e DLQ. Isso comprova os contratos locais pelo broker, não uma Saga E2E com todos os serviços.

Planejamento obrigatório do broker: TLS e identidade por serviço/vhost; OS autorizado a publicar somente os três comandos, Execution a consumir suas filas e publicar eventos/retry/DLX; impedir publicação por produtores alheios e contorno pela default exchange; limitar bindings/configure e management. Provisionar topologia com identidade administrativa separada quando apropriado. ACL efetiva requer validação na infraestrutura; não foi provisionada nesta entrega. Referência: `docs/messaging.md`.

## 10. Preços previstos e segurança financeira

Mecânico com atribuição e capacidade de escrita poderá registrar estimativas de peças/serviços quando o canal confiável for entregue. Preservados precisão decimal, itens, validações e evento `diagnostic.completed.v1`. Essa decisão do escopo atual substitui a pendência genérica D05 da análise anterior apenas quanto a permitir estimativas autorizadas; não concede poder financeiro.

Não aprova orçamento, confirma/altera pagamento ou decide pelo cliente. Aprovação final continua no Billing com o cliente autorizado pela Saga. Não foram criados limites arbitrários de preço. Pendência de negócio: política futura de catálogo, limites ou revisão de estimativas, caso necessária.

## 11. Testes e rastreabilidade de requisitos

| Requisitos | Evidência |
| --- | --- |
| JWT válido/ausente/expirado, assinatura, issuer, audience, sub, roles vazias/malformadas | AuthJwtDecoderTest e HTTP Bearer real em ExecutionApiIntegrationTest |
| none/HS256/RS384, kid, claims faltantes, iat/nbf, TTL, cache e rotação | AuthJwtDecoderTest, chaves RSA efêmeras, JWKS servido por HTTP local e Ticker controlado para expiração aos 60s/atualização/falha sem cache válido |
| papéis insuficientes CLIENTE/ATENDENTE/GERENTE | TechnicalAuthorizationTest e ExecutionApiIntegrationTest |
| mecânico atribuído/não atribuído/revogado; leitura/escrita/conclusão distintas | mocks vinculados ao pedido nos testes de política e HTTP; sem promessa de concessão real no OS |
| decisão de outra OS/usuário/capacidade/correlação; falha/ausência do adapter | TechnicalAuthorizationTest e BootstrapWebIntegrationTest (wiring produtivo indisponível) |
| negadas sem mutação; integridade, estados, concorrência, rollback, cronologia | ExecutionApiIntegrationTest e DomainPersistenceIntegrationTest com MongoDB replica set real |
| adapter preparado exige credencial de serviço e contrato mínimo | TechnicalAuthorizationHttpAdapterTest; transporte simulado, não E2E |
| comandos internos, eventos, Inbox/Outbox, redelivery, retry/DLQ | MessagingIntegrationTest com MongoDB e RabbitMQ reais; testes unitários existentes |

Os testes de assinatura não usam `jwt()` para contornar o decoder. `JwksFixture` gera RSA 2048 efêmera e serve somente JWK público em servidor HTTP loopback; um request factory **somente em src/test** redireciona a URI lógica HTTPS para esse servidor. Decoder produtivo continua exigindo HTTPS; não há perfil/flag de bypass no código produtivo. Fixtures e decisões positivas não existem no JAR de produção. Nenhuma credencial AWS real é necessária.

## 12. Validação e cobertura

Execução final após a correção do cache em **26/10/2026, 10:52:43 America/Sao_Paulo**: `mvn -B clean verify` **BUILD SUCCESS**, duração **3min34s**; **149 testes, zero falhas, zero erros e zero skips**. JaCoCo: **843/866 linhas, 97,34%**, acima dos 80% exigidos. Regra existente preservada, sem novas exclusões, remoção ou skip de testes. Relatórios: `target/surefire-reports` e `target/site/jacoco/index.html`; log completo: `target/jwks-clean-verify.log`. A execução direcionada anterior aprovou os 47 testes de JWT/cache e API HTTP.

| Validação executada | Resultado |
| --- | --- |
| AuthJwtDecoderTest | 17 testes, assinatura RSA/JWKS real, claims, algoritmos, kid ambíguo, expiração/atualização/falha de cache e rotação |
| TechnicalAuthorizationTest | 5 testes de política/decisão/indisponibilidade |
| TechnicalAuthorizationHttpAdapterTest | 3 testes do contrato preparado e credencial técnica |
| ExecutionApiIntegrationTest | 30 testes HTTP RS256 com MongoDB real, incluindo estados/negações/concorrência/rollback; as negações incluem agora os GETs de diagnóstico e reparo por osId, sem DTO técnico |
| BootstrapWebIntegrationTest | 5 testes; probes mínimas e rotas técnicas negadas, wiring produtivo bloqueado |
| MessagingIntegrationTest | 25 testes com RabbitMQ 4 e MongoDB 8 reais; comandos sem JWT humano preservados |
| Demais testes existentes | 64 testes de domínio, aplicação, persistência, codec, publisher e contexto aprovados |
| docker compose config --quiet | exit 0, usando somente valores sintéticos de JWT; nenhuma stack iniciada |
| git diff --check | aprovado |

Comando Maven utilizado: `C:/Users/arneto/.m2/wrapper/dists/apache-maven-3.10.0-bin/39q0qgmngdu329psje7bjgne79/apache-maven-3.10.0/bin/mvn.cmd -B clean verify`, com JAVA_HOME apontando para `C:/Users/arneto/.jdks/ms-21.0.12.1`.

Ambiente: Microsoft JDK 21.0.12.1, Maven 3.10.0 já instalado pelo wrapper, Docker Desktop, imagens MongoDB 8.0/RabbitMQ 4. O javac no sandbox encontrou `Cannot close compiler resources`; validação usa execução autorizada com acesso ao cache Maven, Docker e temporários locais. O wrapper e a configuração do build não foram alterados para contornar o ambiente.

## 13. Riscos e dependências AWS/Kubernetes

- Definir issuer/audience/JWKS por ambiente, DNS/egress HTTPS, CA/truststore e relógios sincronizados; não distribuir chave privada Auth aos pods.
- Projetar canal privado OS com identidade de serviço, credenciais/rotação/delegação e NetworkPolicy; somente então substituir o bean indisponível e homologar resposta/negação/revogação/timeout.
- Probes acessam somente health sem JWT humano; bootstrap/Swagger continuam negados. Planejar acesso técnico independente caso necessário, com identidade e escopo próprios.
- Planejar ACLs/TLS do RabbitMQ e isolamento dos bancos/segredos; não compartilhar bancos ou permissões globais.
- JWT stateless mantém papéis emitidos até expirar (máximo 900s, skew zero); jti não implementa logout ou revogação imediata. Não confundir com revogação de atribuições consultadas por operação.
- Definir retenção/acesso de auditoria sanitizada de decisões (sub, serviço, capacidade, alvo, correlação e resultado), sem tokens/PII/corpos completos; proteção de borda contra abuso do JWKS e de endpoints.

Não foram executados Terraform, AWS, Kubernetes, deploy ou workflows, nem criados recursos/segredos/novos orquestradores. Infraestrutura aqui é dependência planejada.

## 14. Próximos passos e limite da entrega

Revisar esta implementação; obter autorização específica para extensão das capacidades e API interna OS; aprovar autenticação/delegação de serviço; implementar/provisionar concessões auditadas; testar E2E Execution↔OS com duas OS, revogação, concorrência e falhas. Só habilitar o adapter após esses gates. Não iniciar automaticamente 8.3.4.

Sem commit/push; branch e HEAD preservados. A lista final de arquivos consta no diff/status Git; fontes de domínio/aplicação de negócio/mensageria produtiva permanecem intactos.

Conferência final: Execution em homolog no mesmo HEAD, **16 arquivos rastreados modificados e 12 novos**, todos do escopo descrito abaixo. Auth, OS e Billing permanecem em homolog com árvores limpas. Nenhum workflow, Terraform, domínio, caso de uso de negócio, persistência ou fonte produtiva RabbitMQ foi alterado.

## 15. Inventário de arquivos da entrega

Caminhos Java relativos a `src/main/java/com/oficina/execution/`, salvo indicação:

| Arquivo | Mudança |
| --- | --- |
| pom.xml | dependências gerenciadas Resource Server, Spring CaffeineCache e Caffeine |
| compose.yaml | repasse obrigatório da configuração JWT, sem subir stack Compose/deploy |
| README.md | execução/configuração e exemplos Bearer com bloqueio explícito |
| docs/messaging.md | separação JWT HTTP e confiança RabbitMQ |
| integration/README.md | roteiro Basic anterior identificado como histórico, sem executá-lo |
| infrastructure/config/SecurityConfig.java | JWT, stateless, allowlist HTTP e method security |
| infrastructure/config/OpenApiConfig.java | contrato Bearer no esquema OpenAPI |
| infrastructure/web/controller/DiagnosticController.java | autorização por método/recurso/capacidade |
| infrastructure/web/controller/RepairController.java | autorização por método/recurso/capacidade |
| infrastructure/web/controller/ExecutionHistoryController.java | capacidade própria de histórico |
| infrastructure/web/exception/ApiExceptionHandler.java | 403 e 503 sanitizados |
| application/exception/AuthorizationUnavailableException.java | exceção da dependência de autorização |
| application/port/out/TechnicalAuthorizationPort.java | contrato mínimo de decisão |
| infrastructure/security/AuthJwtDecoder.java | assinatura, algoritmo, claims e JWKS |
| infrastructure/security/TechnicalAuthorization.java | política da entrada HTTP |
| infrastructure/security/UnavailableTechnicalAuthorizationAdapter.java | adapter produtivo bloqueado |
| infrastructure/security/TechnicalAuthorizationHttpAdapter.java | adapter HTTPS preparado, sem ativação |
| src/main/resources/application.yml | configuração JWT, probes sem componentes, docs desabilitadas |
| src/test/resources/application.properties | issuer/audience/JWKS sintéticos de teste |
| src/test/java/com/oficina/execution/security/JwksFixture.java | servidor JWKS e RSA efêmeros |
| src/test/java/com/oficina/execution/security/JwtTestConfiguration.java | decoder de testes com transporte local |
| src/test/java/com/oficina/execution/security/AuthJwtDecoderTest.java | assinatura/claims/cache/rotação negativos e positivos |
| src/test/java/com/oficina/execution/security/TechnicalAuthorizationTest.java | política, alvo, negações e indisponibilidade |
| src/test/java/com/oficina/execution/infrastructure/security/TechnicalAuthorizationHttpAdapterTest.java | autenticação de serviço e contrato preparado |
| src/test/java/com/oficina/execution/infrastructure/web/ExecutionApiIntegrationTest.java | HTTP RS256 real, autorização e integridade MongoDB |
| src/test/java/com/oficina/execution/infrastructure/web/BootstrapWebIntegrationTest.java | isolamento técnico, probes e adapter produtivo bloqueado |
| src/test/java/com/oficina/execution/infrastructure/messaging/MessagingIntegrationTest.java | REST com JWT real; comandos sem contexto humano |
| docs/security/execution-authorization-8.3.3.md | arquitetura, matriz, dependências, evidências e riscos |
