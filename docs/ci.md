# CI/CD do Execution Service

Fonte: [workflow ci.yml](../.github/workflows/ci.yml), [verificador](../scripts/ci/verify.py), [Compose de runtime](../scripts/ci/compose.yaml), [Dockerfile](../Dockerfile) e [pom.xml](../pom.xml).

## Gatilhos

O workflow `Execution CI` executa em pull requests e pushes para `main` e `homolog`, além de `workflow_dispatch`. Possui os jobs `verify` e `deploy`.

## Job verify

Executa em `ubuntu-22.04`, com timeout de 45 minutos e Java Temurin 21:

1. Checkout com histórico completo e sem persistência de credenciais.
2. Testes dos scripts CI via `python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'`.
3. `scripts/ci/verify.py`: scanner básico de segredos, validação de Java 21 e Maven Wrapper com `-B -ntp clean verify`.
4. Conferência dos relatórios Maven: testes presentes e zero falhas, erros ou ignorados; cobertura JaCoCo LINE mínima de 80%. O POM também aplica o gate `BUNDLE`/`LINE`/`COVEREDRATIO` de 0,80 em `verify`.
5. Build Docker para `linux/amd64`; validação de usuário não root, entrypoint Java, healthcheck de readiness, SIGTERM e ausência de segredos detectados nas variáveis da imagem.
6. Runtime com MongoDB 8.0 autenticado em replica set `rs0` e RabbitMQ 4, em projeto Compose temporário: usuário não root, Java 21 e os três endpoints de health retornando `UP`.
7. Parada por SIGTERM em até 30 segundos, sem OOM, com saída 0 ou 143; remoção de containers, rede e volume temporários em `finally`.
8. Análise SonarCloud com o XML `target/site/jacoco/jacoco.xml`, organização `auronet0` e project key `AuroNet0_tech-challenge-oficina-execution-service`.

`sonar.qualitygate.wait` é `true` somente em push na `main`: nesse caso o Quality Gate bloqueia a continuidade. A análise é executada também nos demais gatilhos; ausência de `SONAR_TOKEN` ou falha do scanner reprova o job.

A coleta de resultados executa mesmo em falha. `job.json` registra revisão e estado do job; `verification.json` registra os gates do verificador, incluindo itens não executados como `not_run`. Apenas os JSONs sanitizados são publicados no artefato `execution-ci-${{ github.sha }}`, com retenção de sete dias.

O scanner básico procura padrões de chaves privadas, identificadores AWS, tokens GitHub, URLs com credenciais e atribuições literais de segredos conforme o tipo de arquivo. Não imprime os valores encontrados e não garante detectar todo segredo possível. A inspeção profunda de binários de terceiros fica `not_run` no relatório do verificador.

## Job deploy

Depende de `verify` e roda somente em push na `main`, em `ubuntu-latest`. Usa OIDC (`id-token: write`) para assumir a role indicada por `vars.AWS_ROLE_ARN`. A concorrência do grupo `deploy-execution-service` não cancela um deploy em andamento.

O preflight consulta o cluster EKS e o repositório ECR. `ResourceNotFoundException` ou `RepositoryNotFoundException` produzem skip do deploy, permitindo o ambiente desligado. Outros erros falham; não são tratados como ambiente ausente. A configuração da role ocorre antes do preflight.

Com ambos presentes, faz checkout de `github.sha`. Procura uma imagem com tag igual ao SHA completo: reutiliza o digest quando já existe; caso contrário, autentica no ECR, faz build e push. Esse procedimento evita sobrescrever deliberadamente uma tag existente; a política de imutabilidade do repositório ECR não é configurada pelo workflow.

Após resolver e validar o digest, atualiza o kubeconfig e executa `kubectl set image` com a referência por digest. Aguarda `kubectl rollout status` por até 300 segundos; em falha coleta descrição e logs e retorna erro. Não executa rollback automático. O resumo registra serviço, SHA, digest e resultado de deploy ou skip.

| Configuração | Valor |
| --- | --- |
| `AWS_REGION` | `us-east-1` |
| `CLUSTER` | `tc-oficina-p4-demo-a` |
| `NAMESPACE` | `tc-oficina-p4-demo-a` |
| `ECR_REPOSITORY` | `tc-oficina-p4-demo-a-execution` |
| `DEPLOYMENT` / `CONTAINER` | `execution-service` / `execution-service` |

O endereço do registro é obtido a partir da conta retornada por `aws sts get-caller-identity`. O workflow pressupõe o Deployment existente; sua leitura não comprova o estado do ambiente.

## Secrets e variáveis

| Nome | Tipo | Uso |
| --- | --- | --- |
| `SONAR_TOKEN` | Secret GitHub Actions | Análise SonarCloud |
| `AWS_ROLE_ARN` | Variável GitHub Actions | Role de deploy assumida via OIDC |
| `GITHUB_TOKEN` | Token automático do GitHub Actions | Repassado ao scanner SonarCloud |

## Verificação local

Requisitos do verificador: Git no PATH, Python 3, Java 21 e Docker com Compose v2 e containers Linux. Os testes Maven iniciam MongoDB/RabbitMQ via Testcontainers, sem exigir outros microsserviços.

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -B scripts/ci/verify.py --output target/ci-verification
git diff --check
```

No Windows, use `python` no lugar de `python3` conforme a instalação. O diretório de saída é criado após o Maven, pois `clean` remove `target`. O scanner considera arquivos versionados e novos não ignorados.

Para validar os YAMLs, o [validador existente](../scripts/ci/validate_yaml.py) usa PyYAML no ambiente de ferramentas: `python3 -B scripts/ci/validate_yaml.py`.

## Correção documental

A versão anterior deste documento descrevia somente `homolog` e afirmava ausência de publicação e deploy. Essa descrição foi substituída pelo comportamento do workflow atual. Evidências históricas de execução local não constituem comprovação de execução atual do CI, do SonarCloud ou do deploy.
