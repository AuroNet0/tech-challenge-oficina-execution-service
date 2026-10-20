param(
    [string]$ProjectName = "oficina-integration-65",
    [string]$ArtifactDirectory = "",
    [switch]$SkipBuild,
    [switch]$KeepEnvironment
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$workspace = Split-Path $PSScriptRoot -Parent
if (-not $ArtifactDirectory) { $ArtifactDirectory = Join-Path $workspace ("target/integration-65/" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
New-Item -ItemType Directory -Force -Path $ArtifactDirectory | Out-Null
$ArtifactDirectory = (Resolve-Path $ArtifactDirectory).Path
if ($ProjectName -notmatch '^oficina-integration-65(?:-[a-z0-9-]+)?$') { throw "Use um projeto exclusivo com prefixo oficina-integration-65." }
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else { "C:/Program Files/Docker/Docker/resources/bin/docker.exe" }
$composeFile = Join-Path $PSScriptRoot "compose.yaml"
$composePrefix = @("compose", "-p", $ProjectName, "-f", $composeFile)
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("integration:integration_only"))
$headers = @{ Authorization = "Basic " + $basic }
$osPort = if ($env:INTEGRATION_OS_PORT) { $env:INTEGRATION_OS_PORT } else { "18080" }
$executionPort = if ($env:INTEGRATION_EXECUTION_PORT) { $env:INTEGRATION_EXECUTION_PORT } else { "18081" }
$rabbitPort = if ($env:INTEGRATION_RABBIT_MANAGEMENT_PORT) { $env:INTEGRATION_RABBIT_MANAGEMENT_PORT } else { "18572" }
$osApi = "http://127.0.0.1:$osPort"
$executionApi = "http://127.0.0.1:$executionPort"
$rabbitApi = "http://127.0.0.1:$rabbitPort/api"
$script:stage = "preparacao"
$script:osId = $null
$script:brokerStopped = $false
$result = [ordered]@{ startedAt = [DateTime]::UtcNow.ToString("o"); project = $ProjectName; success = $false; checks = @() }

function Invoke-Native {
    param([string[]]$Arguments, [string]$InputText)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        if ($PSBoundParameters.ContainsKey("InputText")) { $nativeOutput = $InputText | & $docker @Arguments 2>&1 }
        else { $nativeOutput = & $docker @Arguments 2>&1 }
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = ($nativeOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if ($exitCode -ne 0) { throw "Docker exit=$exitCode; $text" }
    return $text
}
function Invoke-Compose {
    param([string[]]$Arguments, [string]$InputText)
    $parameters = @{ Arguments = $composePrefix + $Arguments }
    if ($PSBoundParameters.ContainsKey("InputText")) { $parameters.InputText = $InputText }
    Invoke-Native @parameters
}
function Save-Evidence {
    param([string]$Name, $Data)
    ConvertTo-Json -InputObject $Data -Depth 80 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $ArtifactDirectory ($Name + ".json"))
}
function Assert-Check {
    param([bool]$Condition, [string]$Description)
    if (-not $Condition) { throw "Assertion: $Description" }
    $result.checks += $Description
    Write-Host ("OK " + $Description)
}
function Wait-Condition {
    param([string]$Name, [scriptblock]$Condition, [int]$TimeoutSeconds = 90)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $lastFailure = ""
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $value = & $Condition
            if ($null -ne $value -and $value -ne $false) { return $value }
        } catch { $lastFailure = $_.Exception.Message }
        Start-Sleep -Milliseconds 500
    }
    throw "Timeout aguardando $Name. Ultima falha: $lastFailure"
}
function Invoke-Api {
    param([string]$Uri, [string]$Method = "GET", $Body)
    $parameters = @{ Uri = $Uri; Method = $Method; Headers = $headers; TimeoutSec = 10; UseBasicParsing = $true }
    if ($PSBoundParameters.ContainsKey("Body")) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 40 -Compress))
    }
    $response = Invoke-RestMethod @parameters
    if ($response -is [Array]) {
        foreach ($entry in $response) { Write-Output $entry }
    } else {
        Write-Output $response
    }
}
function Read-PgSnapshot {
    param([string]$OrderId)
    [void][Guid]::Parse($OrderId)
    $sql = @"
SELECT json_build_object(
'os', (SELECT row_to_json(record) FROM (SELECT * FROM ordens_servico WHERE id='$OrderId'::uuid) record),
'saga', (SELECT row_to_json(record) FROM (SELECT * FROM saga_instances WHERE ordem_servico_id='$OrderId'::uuid) record),
'outbox', (SELECT COALESCE(json_agg(record),'[]'::json) FROM (SELECT * FROM outbox_events WHERE aggregate_id='$OrderId'::uuid ORDER BY created_at,id) record),
'inbox', (SELECT COALESCE(json_agg(record),'[]'::json) FROM (SELECT * FROM inbox_messages WHERE saga_id=(SELECT id FROM saga_instances WHERE ordem_servico_id='$OrderId'::uuid) ORDER BY received_at,event_id) record),
'history', (SELECT COALESCE(json_agg(record),'[]'::json) FROM (SELECT * FROM historico_status WHERE ordem_servico_id='$OrderId'::uuid ORDER BY created_at,id) record));
"@
    $output = Invoke-Compose -Arguments @("exec", "-T", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
        "postgres", "psql", "-X", "-A", "-t", "-U", "integration", "-d", "oficina_os", "-v", "ON_ERROR_STOP=1", "-c", $sql)
    return ConvertFrom-Json -InputObject $output
}
function Read-MongoSnapshot {
    param([string]$OrderId)
    [void][Guid]::Parse($OrderId)
    $javascript = @"
const store = db.getSiblingDB('oficina_execution');
const filter = {osId:'$OrderId'};
print(JSON.stringify({
 primary:db.hello().isWritablePrimary,
 diagnostics:store.diagnostics.find(filter).sort({_id:1}).toArray(),
 inbox:store.inbox_messages.find(filter).sort({_id:1}).toArray(),
 history:store.execution_history.find(filter).sort({_id:1}).toArray(),
 outbox:store.outbox_events.find(filter).sort({_id:1}).toArray()
}));
"@
    $output = Invoke-Compose -Arguments @("exec", "-T", "mongodb", "mongosh", "--quiet", "-u", "integration",
        "-p", "integration_only", "--authenticationDatabase", "admin", "--file", "/dev/stdin") -InputText $javascript
    return ConvertFrom-Json -InputObject $output
}
function Snapshot-Hash {
    param($Snapshot)
    $json = ConvertTo-Json -InputObject $Snapshot -Depth 80 -Compress
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace("-", "") }
    finally { $hash.Dispose() }
}
function Get-AckCount {
    param([string]$Queue)
    $state = Invoke-Api "$rabbitApi/queues/%2F/$Queue"
    $stats = $state.PSObject.Properties["message_stats"]
    if ($stats -and $stats.Value.PSObject.Properties["ack"]) { return [long]$stats.Value.ack }
    return 0
}
function Wait-AckCount {
    param([string]$Queue, [long]$Minimum)
    Wait-Condition "ACK de $Queue >= $Minimum" {
        $count = Get-AckCount $Queue
        if ($count -ge $Minimum) { return $count }
        return $false
    } | Out-Null
}
function Observe-Message {
    param([string]$Queue, [string]$EventType, [string]$OrderId)
    $messages = @(Invoke-Api "$rabbitApi/queues/%2F/$Queue/get" "POST" @{
        count = 100; ackmode = "ack_requeue_true"; encoding = "auto"; truncate = 100000
    })
    return @($messages | Where-Object {
        $envelope = ConvertFrom-Json -InputObject $_.payload
        $envelope.eventType -eq $EventType -and $envelope.osId -eq $OrderId
    })
}
function Wait-Message {
    param([string]$Queue, [string]$EventType, [string]$OrderId)
    Wait-Condition "mensagem $EventType" {
        $observed = @(Observe-Message $Queue $EventType $OrderId)
        if ($observed.Count -gt 0) { return $observed[0] }
        return $false
    }
}
function Assert-Envelope {
    param($Message, [string]$EventType, [string]$Source, [string]$SagaId, [string]$CorrelationId)
    $envelope = ConvertFrom-Json -InputObject $Message.payload
    foreach ($field in @("eventId", "eventType", "occurredAt", "correlationId", "sagaId", "osId", "source", "payload")) {
        Assert-Check ($null -ne $envelope.PSObject.Properties[$field]) "$EventType contem $field"
    }
    foreach ($field in @("eventId", "correlationId", "sagaId", "osId")) {
        Assert-Check (([Guid]::Parse($envelope.$field)).ToString() -eq $envelope.$field) "$EventType UUID canonico: $field"
    }
    Assert-Check ($envelope.occurredAt -is [string]) "$EventType occurredAt deve ser string ISO-8601"
    [void][DateTimeOffset]::Parse($envelope.occurredAt, [Globalization.CultureInfo]::InvariantCulture)
    Assert-Check ($envelope.occurredAt -match '^\d{4}-\d{2}-\d{2}T') "$EventType occurredAt ISO-8601"
    Assert-Check ($envelope.eventType -eq $EventType -and $Message.routing_key -eq $EventType) "$EventType routing key e envelope iguais"
    Assert-Check ($envelope.source -eq $Source -and $envelope.osId -eq $script:osId) "$EventType origem e OS corretas"
    Assert-Check ($envelope.sagaId -eq $SagaId -and $envelope.correlationId -eq $CorrelationId) "$EventType preserva Saga e correlacao"
    Assert-Check ($Message.properties.delivery_mode -eq 2) "$EventType persistente"
    return $envelope
}
function Replay-Message {
    param($Message, [string]$Exchange)
    $envelope = ConvertFrom-Json -InputObject $Message.payload
    $published = Invoke-Api "$rabbitApi/exchanges/%2F/$Exchange/publish" "POST" @{
        properties = @{ content_type = "application/json"; delivery_mode = 2; message_id = $envelope.eventId; correlation_id = $envelope.correlationId }
        routing_key = $envelope.eventType; payload = $Message.payload; payload_encoding = "string"
    }
    Assert-Check ([bool]$published.routed) "Replay original roteado: $($envelope.eventType)"
}
function Stop-Broker {
    Invoke-Compose -Arguments @("stop", "rabbitmq") | Out-Null
    $script:brokerStopped = $true
}
function Start-Broker {
    Invoke-Compose -Arguments @("start", "rabbitmq") | Out-Null
    Wait-Condition "RabbitMQ restaurado" { Invoke-Api "$rabbitApi/overview" } | Out-Null
    $script:brokerStopped = $false
}
function New-TestCpf {
    $digits = @(1..9 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
    foreach ($length in @(9, 10)) {
        $sum = 0
        for ($index = 0; $index -lt $length; $index++) { $sum += $digits[$index] * ($length + 1 - $index) }
        $digit = 11 - ($sum % 11)
        if ($digit -ge 10) { $digit = 0 }
        $digits += $digit
    }
    return $digits -join ""
}
function Read-Containers {
    $jsonLines = Invoke-Compose -Arguments @("ps", "--format", "json")
    $entries = ConvertFrom-Json -InputObject ("[" + (($jsonLines -split [char]10) -join ",") + "]")
    foreach ($entry in $entries) { Write-Output $entry }
}
try {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    $git = if ($gitCommand) { $gitCommand.Source } else { "C:/Program Files/Git/cmd/git.exe" }
    $osRepository = Join-Path (Split-Path $workspace -Parent) "tech-challenge-oficina-os-service"
    foreach ($repository in @($workspace, $osRepository)) {
        $branchName = & $git -c "safe.directory=$repository" -C $repository branch --show-current
        Assert-Check ($LASTEXITCODE -eq 0 -and $branchName -eq "homolog") "Branch homolog: $(Split-Path $repository -Leaf)"
    }
    $script:stage = "inicializacao"
    Invoke-Compose -Arguments @("config", "--quiet") | Out-Null
    $startupArguments = @("up", "-d", "--wait", "--wait-timeout", "180")
    if (-not $SkipBuild) { $startupArguments += "--build" }
    Invoke-Compose -Arguments $startupArguments | Set-Content -Encoding UTF8 (Join-Path $ArtifactDirectory "startup.log")
    $containers = @(Read-Containers)
    Save-Evidence "containers-initial" $containers
    Assert-Check ($containers.Count -eq 5 -and @($containers | Where-Object { $_.Health -ne "healthy" }).Count -eq 0) "Cinco containers saudaveis, com um unico broker"
    foreach ($service in @($osApi, $executionApi)) {
        Assert-Check ((Invoke-Api "$service/actuator/health/readiness").status -eq "UP") "Readiness UP: $service"
    }
    $exchanges = @(Invoke-Api "$rabbitApi/exchanges/%2F")
    Save-Evidence "rabbit-exchanges" $exchanges
    Save-Evidence "rabbit-bindings" (Invoke-Api "$rabbitApi/bindings/%2F")
    foreach ($name in @("oficina.commands", "oficina.events")) {
        Assert-Check (@($exchanges | Where-Object { $_.name -eq $name -and $_.type -eq "topic" -and $_.durable }).Count -eq 1) "Exchange topic duravel $name"
    }
    foreach ($observer in @("commands", "events")) {
        Invoke-Api "$rabbitApi/queues/%2F/integration.observe.$observer" "PUT" @{ durable = $true; auto_delete = $false; arguments = @{} } | Out-Null
        Invoke-Api "$rabbitApi/bindings/%2F/e/oficina.$observer/q/integration.observe.$observer" "POST" @{ routing_key = "#"; arguments = @{} } | Out-Null
    }
    $bindings = @(Invoke-Api "$rabbitApi/bindings/%2F")
    foreach ($binding in @(
        @("oficina.commands", "execution.diagnostic.start", "diagnostic.start.v1"),
        @("oficina.commands", "execution.repair.start", "repair.start.v1"),
        @("oficina.commands", "execution.repair.cancel", "repair.cancel.v1"),
        @("oficina.events", "os.saga.events", "#.v1")
    )) {
        Assert-Check (@($bindings | Where-Object { $_.source -eq $binding[0] -and $_.destination -eq $binding[1] -and $_.routing_key -eq $binding[2] }).Count -eq 1) "Binding $($binding -join ' -> ')"
    }
    $script:stage = "cadastros REST"
    $customer = Invoke-Api "$osApi/api/v1/clientes" "POST" @{ nome = "Cliente Integracao Isolada"; cpfCnpj = New-TestCpf }
    $branch = Invoke-Api "$osApi/api/v1/filiais" "POST" @{
        nome = "Filial Integracao"; codigo = "INT-" + [Guid]::NewGuid().ToString("N").Substring(0, 8); cidade = "Sao Paulo"; uf = "SP"
    }
    $vehicle = Invoke-Api "$osApi/api/v1/veiculos" "POST" @{
        clienteId = $customer.id; placa = "TST" + (Get-Random -Minimum 1000 -Maximum 10000); marca = "Teste"; modelo = "Integracao"; ano = 2025
    }
    $script:stage = "abertura com broker indisponivel"
    Stop-Broker
    $order = Invoke-Api "$osApi/api/v1/ordens-servico" "POST" @{
        clienteId = $customer.id; filialId = $branch.id; veiculoId = $vehicle.id; observacoes = "Diagnostico integrado isolado"
    }
    $script:osId = $order.id
    $result.osId = $order.id
    $saga = Invoke-Api "$osApi/api/v1/ordens-servico/$($order.id)/saga"
    $result.sagaId = $saga.sagaId
    Assert-Check ($order.status -eq "RECEBIDA") "OS inicia RECEBIDA"
    Assert-Check ($saga.status -eq "WAITING_DIAGNOSTIC") "Saga inicial WAITING_DIAGNOSTIC"
    $pgPending = Wait-Condition "tentativa da Outbox OS sem broker" {
        $snapshot = Read-PgSnapshot $script:osId
        $pending = @($snapshot.outbox | Where-Object { $_.event_type -eq "diagnostic.start.v1" -and $_.attempts -ge 1 -and $_.status -eq "PENDING" })
        if ($pending.Count -eq 1) { return $snapshot }
        return $false
    }
    Save-Evidence "os-outbox-during-outage" $pgPending
    Assert-Check ($pgPending.os.status -eq "RECEBIDA") "OS e Outbox confirmadas preservadas durante indisponibilidade"
    Start-Broker
    $script:stage = "contrato diagnostic.start.v1"
    $initialCommand = Wait-Message "integration.observe.commands" "diagnostic.start.v1" $script:osId
    Save-Evidence "wire-diagnostic-start-command" $initialCommand
    $initialEnvelope = ConvertFrom-Json -InputObject $initialCommand.payload
    $correlationId = $initialEnvelope.correlationId
    $result.correlationId = $correlationId
    $startEnvelope = Assert-Envelope $initialCommand "diagnostic.start.v1" "os-service" $saga.sagaId $correlationId
    $script:stage = "diagnostico iniciado e OS EM_DIAGNOSTICO"
    $diagnostic = Wait-Condition "diagnostico IN_PROGRESS" {
        $value = Invoke-Api "$executionApi/api/v1/diagnosticos?osId=$script:osId"
        if ($value.status -eq "IN_PROGRESS") { return $value }
        return $false
    }
    $result.diagnosticId = $diagnostic.id
    Wait-Condition "OS EM_DIAGNOSTICO" { (Invoke-Api "$osApi/api/v1/ordens-servico/$script:osId").status -eq "EM_DIAGNOSTICO" } | Out-Null
    $startedEvent = Wait-Message "integration.observe.events" "diagnostic.started.v1" $script:osId
    $startedEnvelope = Assert-Envelope $startedEvent "diagnostic.started.v1" "execution-service" $saga.sagaId $correlationId
    Assert-Check ($startEnvelope.payload.clienteId -eq $customer.id -and $startEnvelope.payload.veiculoId -eq $vehicle.id -and $startEnvelope.payload.filialId -eq $branch.id) "Comando contem referencias reais cadastradas pela API"
    Save-Evidence "wire-diagnostic-start-command" $initialCommand
    Save-Evidence "wire-diagnostic-started-event" $startedEvent
    $mongoBefore = Wait-Condition "Inbox processada e evento started publicado" {
        $snapshot = Read-MongoSnapshot $script:osId
        if (@($snapshot.inbox | Where-Object { $_.status -eq "PROCESSED" }).Count -eq 1 -and @($snapshot.outbox | Where-Object { $_.status -eq "PUBLISHED" }).Count -eq 1) { return $snapshot }
        return $false
    }
    Assert-Check ($mongoBefore.primary -eq $true -and @($mongoBefore.diagnostics).Count -eq 1) "MongoDB PRIMARY e diagnostico unico persistido"
    $script:stage = "idempotencia diagnostic.start.v1"
    Wait-AckCount "execution.diagnostic.start" 1
    $ackBefore = Get-AckCount "execution.diagnostic.start"
    Replay-Message $initialCommand "oficina.commands"
    Wait-AckCount "execution.diagnostic.start" ($ackBefore + 1)
    $mongoAfter = Read-MongoSnapshot $script:osId
    Save-Evidence "execution-before-redelivery" $mongoBefore
    Save-Evidence "execution-after-redelivery" $mongoAfter
    Assert-Check ((Snapshot-Hash $mongoBefore) -eq (Snapshot-Hash $mongoAfter)) "Redelivery start: diagnostico, Inbox, historico e Outbox identicos"
    Assert-Check (@(Observe-Message "integration.observe.events" "diagnostic.started.v1" $script:osId).Count -eq 1) "Nenhum diagnostic.started.v1 adicional"
    $result.startIdempotent = $true
    $script:stage = "item tecnico e conclusao durante indisponibilidade"
    $item = [ordered]@{ itemId = "item-001"; descricao = "Troca de pastilhas de freio"; tipo = "PECA"; quantidade = "2.500"; unidadePreco = "UNIDADE"; precoPrevisto = "150.000100" }
    $updated = Invoke-Api "$executionApi/api/v1/diagnosticos/$($diagnostic.id)/itens" "POST" $item
    Assert-Check ($updated.items[0].quantidade -eq $item.quantidade -and $updated.items[0].precoPrevisto -eq $item.precoPrevisto) "REST preserva precisao decimal"
    Stop-Broker
    $completed = Invoke-Api "$executionApi/api/v1/diagnosticos/$($diagnostic.id)/conclusao" "POST"
    Assert-Check ($completed.status -eq "COMPLETED") "Conclusao REST confirmada mesmo sem broker"
    $mongoPending = Wait-Condition "tentativa Outbox Execution sem broker" {
        $snapshot = Read-MongoSnapshot $script:osId
        $pending = @($snapshot.outbox | Where-Object { $_.eventType -eq "diagnostic.completed.v1" -and $_.attempts -ge 1 -and $_.status -eq "PENDING" })
        if ($pending.Count -eq 1) { return $snapshot }
        return $false
    }
    Save-Evidence "execution-outbox-during-outage" $mongoPending
    Assert-Check ($mongoPending.diagnostics[0].status -eq "COMPLETED" -and @($mongoPending.history).Count -eq 3) "Agregado, historico e evento de conclusao preservados"
    $duringOutageSaga = Invoke-Api "$osApi/api/v1/ordens-servico/$script:osId/saga"
    Assert-Check ($duringOutageSaga.status -eq "WAITING_DIAGNOSTIC") "Saga nao avanca antes da entrega real"
    Start-Broker
    $script:stage = "conclusao entregue e WAITING_QUOTE"
    $completedEvent = Wait-Message "integration.observe.events" "diagnostic.completed.v1" $script:osId
    $completedEnvelope = Assert-Envelope $completedEvent "diagnostic.completed.v1" "execution-service" $saga.sagaId $correlationId
    foreach ($field in $item.Keys) { Assert-Check ($completedEnvelope.payload.items[0].$field -ceq $item[$field]) "Item publicado preserva $field" }
    Assert-Check ($completedEnvelope.payload.items[0].quantidade -is [string] -and $completedEnvelope.payload.items[0].precoPrevisto -is [string]) "Numeros publicados como strings decimais"
    $finalSaga = Wait-Condition "Saga WAITING_QUOTE" {
        $value = Invoke-Api "$osApi/api/v1/ordens-servico/$script:osId/saga"
        if ($value.status -eq "WAITING_QUOTE") { return $value }
        return $false
    }
    $quoteCommand = Wait-Message "integration.observe.commands" "quote.create.v1" $script:osId
    $quoteEnvelope = Assert-Envelope $quoteCommand "quote.create.v1" "os-service" $saga.sagaId $correlationId
    Assert-Check (@($quoteEnvelope.payload.items).Count -eq @($completedEnvelope.payload.items).Count) "OS repassa todos os itens a quote.create.v1"
    foreach ($field in $item.Keys) {
        Assert-Check ($quoteEnvelope.payload.items[0].$field -ceq $completedEnvelope.payload.items[0].$field) "quote.create.v1 preserva $field"
    }
    Save-Evidence "wire-diagnostic-completed-event" $completedEvent
    Save-Evidence "wire-quote-create-command" $quoteCommand
    $pgBefore = Wait-Condition "Outbox OS publicada e Inbox processada" {
        $snapshot = Read-PgSnapshot $script:osId
        if (@($snapshot.outbox | Where-Object { $_.status -eq "PUBLISHED" }).Count -eq 2 -and @($snapshot.inbox | Where-Object { $_.status -eq "PROCESSED" }).Count -eq 2) { return $snapshot }
        return $false
    }
    Assert-Check ($pgBefore.os.status -eq "EM_DIAGNOSTICO" -and $pgBefore.saga.status -eq "WAITING_QUOTE") "OS permanece EM_DIAGNOSTICO sem quote.created.v1"
    $script:stage = "idempotencia diagnostic.completed.v1"
    Wait-AckCount "os.saga.events" 1
    $ackBefore = Get-AckCount "os.saga.events"
    Replay-Message $completedEvent "oficina.events"
    Wait-AckCount "os.saga.events" ($ackBefore + 1)
    $pgAfter = Read-PgSnapshot $script:osId
    Save-Evidence "os-before-redelivery" $pgBefore
    Save-Evidence "os-after-redelivery" $pgAfter
    Assert-Check ((Snapshot-Hash $pgBefore) -eq (Snapshot-Hash $pgAfter)) "Redelivery completed: OS, Saga, Inbox, Outbox e historico identicos"
    Assert-Check (@(Observe-Message "integration.observe.commands" "quote.create.v1" $script:osId).Count -eq 1) "Nenhum quote.create.v1 adicional"
    $result.completedIdempotent = $true
    $result.brokerRecovery = @{ osPublisher = $true; executionPublisher = $true }
    $result.eventIds = @{
        diagnosticStart = $initialEnvelope.eventId; diagnosticStarted = $startedEnvelope.eventId
        diagnosticCompleted = $completedEnvelope.eventId; quoteCreate = $quoteEnvelope.eventId
    }
    Assert-Check (@($result.eventIds.Values | Select-Object -Unique).Count -eq 4) "Quatro envelopes originais com eventIds proprios"
    $result.finalOsStatus = $pgAfter.os.status
    $result.finalSagaStatus = $pgAfter.saga.status
    $result.finalDiagnosticStatus = $completed.status
    $result.health = @(Read-Containers)
    Assert-Check (@($result.health | Where-Object { $_.Health -ne "healthy" }).Count -eq 0) "Todos os containers saudaveis apos recuperacao"
    $result.success = $true
} catch {
    $result.failureStage = $script:stage
    $result.error = $_.Exception.Message
    Write-Host ("FALHA em " + $script:stage + ": " + $_.Exception.Message)
    throw
} finally {
    $result.finishedAt = [DateTime]::UtcNow.ToString("o")
    try {
        if ($script:brokerStopped) { Start-Broker }
        Invoke-Compose -Arguments @("logs", "--no-color", "--tail", "500") | Set-Content -Encoding UTF8 (Join-Path $ArtifactDirectory "services.log")
        if ($script:osId) {
            Save-Evidence "mongo-final" (Read-MongoSnapshot $script:osId)
            Save-Evidence "postgres-final" (Read-PgSnapshot $script:osId)
            Save-Evidence "execution-dlq" @(Invoke-Api "$rabbitApi/queues/%2F/execution.commands.dlq/get" "POST" @{
                count = 100; ackmode = "ack_requeue_true"; encoding = "auto"; truncate = 100000
            })
        }
    } catch { $result.collectionError = $_.Exception.Message }
    Save-Evidence "result" $result
    Write-Host ("Evidencias: " + $ArtifactDirectory)
    if (-not $KeepEnvironment) {
        Invoke-Compose -Arguments @("down", "--volumes", "--remove-orphans") | Set-Content -Encoding UTF8 (Join-Path $ArtifactDirectory "cleanup.log")
    }
}
