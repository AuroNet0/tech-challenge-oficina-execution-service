# CI do Execution Service

O workflow `.github/workflows/ci.yml` verifica PRs e pushes para `homolog`
e aceita execução manual. Não publica imagens nem executa deploy.
Segue o escopo acadêmico simplificado do OS Service (commit
`2aea68c`), usando apenas este repositório.

## Gates obrigatórios

1. Testes dos scripts CI.
2. Scanner básico de fontes e configurações.
3. Java 21 e `./mvnw -B -ntp clean verify`.
4. Relatórios de testes sem falhas, erros ou testes ignorados e JaCoCo LINE >= 80%.
5. Build Docker `linux/amd64`, usuário não root, healthcheck e SIGTERM.
6. Inicialização com MongoDB 8.0 autenticado, replica set `rs0` e RabbitMQ 4;
   `/actuator/health`, `/actuator/health/liveness` e
   `/actuator/health/readiness` devem retornar `UP`.
7. Parada por SIGTERM em até 30 segundos, sem OOM ou encerramento forçado
   (código de saída 0 ou 143).

Qualquer falha retorna código diferente de zero e reprova o job. A coleta de
relatórios roda mesmo em falha e não altera esse resultado. `job.json` registra
a revisão e o estado do job; `verification.json` registra cada gate, métricas
Maven/JaCoCo e runtime. Gates não executados ficam como `not_run`.
Somente esses JSONs são publicados como artefatos, com retenção de sete dias.

## Execução local

Requisitos: Git, Python 3, Java 21 e Docker com Compose v2 e containers Linux.
Os testes Maven iniciam seus próprios MongoDB/RabbitMQ via Testcontainers,
incluindo suporte a transações MongoDB. Nenhum outro microsserviço é necessário.

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -B scripts/ci/verify.py --output target/ci-verification
git diff --check
```

No Windows, usar `python` no lugar de `python3` conforme a instalação.
O diretório de saída é criado após o Maven, pois `clean` remove `target`.
O scanner considera arquivos versionados e arquivos novos não ignorados.

Para validar os YAMLs, instalar PyYAML no ambiente de ferramentas e executar
`python3 -B scripts/ci/validate_yaml.py`. PyYAML não é dependência da aplicação
nem do verificador principal.

O build Docker usa apenas `.mvn`, wrapper, POM e fontes no estágio de compilação;
`target` é excluído do contexto. As imagens-base Java 21 existentes são mantidas.
O smoke test cria um projeto Compose exclusivo, sem portas publicadas, com rede
interna e senha aleatória efêmera. Containers, rede e volume desse projeto são
removidos em `finally`, inclusive em falha. Os endpoints JWT fictícios usam HTTPS;
o teste de health não solicita JWT nem depende do Auth.

## Escopo de segurança

O scanner procura chaves privadas, identificadores AWS, tokens GitHub e URLs com
credenciais nos arquivos do repositório. Também recusa atribuições literais de
segredos nas fontes principais e configurações de build/CI/runtime. Referências
a variáveis de ambiente são aceitas. As senhas locais já documentadas em
placeholders e as fixtures de integração não representam credenciais de ambiente
real. O scanner não imprime valores encontrados.

É uma verificação básica por padrões, não uma garantia de ausência de todo
segredo possível. Não há inspeção profunda de JARs ou bibliotecas de terceiros,
nem exceções para Netty/GnuTLS. Essa inspeção fica explicitamente `not_run` e
não é um gate. O scanner central da Infra não é utilizado nem modificado.

Nenhuma regra de negócio, contrato HTTP/JWT, Inbox/Outbox, idempotência ou
configuração de transações da aplicação é alterada por este CI.

## Validação local em 08/11/2026

- Origem confirmada: `AuroNet0/tech-challenge-oficina-execution-service`;
  branch `homolog`, HEAD `929c6de05e6b482d82fd097e6cd61ac1d38f9b30`,
  árvore inicialmente limpa.
- Maven `clean verify` com Java 21: 157 testes, zero falhas, erros ou ignorados.
- JaCoCo LINE: 877 cobertas / 927 totais = 94,61%; gate de 80% aprovado.
- Sete testes dos scripts CI aprovados, incluindo propagação de falhas,
  scanner, cobertura, contrato da imagem e limpeza após falha de startup.
- Workflow e Compose validados com PyYAML; `git diff --check` aprovado.
- Build multistage `linux/amd64` aprovado, independente do `target` local.
- Imagem sem credenciais detectadas em suas variáveis; runtime Java 21,
  usuário `app` com UID 100, três endpoints de health `UP`.
- MongoDB autenticado com replica set e RabbitMQ saudáveis no smoke test.
- SIGTERM aprovado, saída 143, sem OOM; recursos Compose temporários removidos.
- Scanner básico de fontes/configurações aprovado. Relatórios locais ficam em
  `target/ci-local-quality.json` e `target/ci-local-runtime.json`.
- Inspeção profunda de binários de terceiros e GitHub Actions remoto não
  executados. AWS, ECR push, deploy, Terraform, commit e push não executados.
- Nenhum bloqueador funcional identificado nas verificações executadas.
