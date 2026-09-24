param(
    [Parameter(Mandatory)][ValidateSet('policy-insertion', 'snapshot-insertion', 'bounded-early', 'flexible-final')][string[]]$Stages,
    [Parameter(Mandatory)][ValidateRange(18000, 18099)][int]$Port
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$reference = Get-Content (Join-Path $repository 'docs/evidence/booking-four-stages-2026-09-24.json') -Raw | ConvertFrom-Json
$java = 'C:\Program Files\Java\jdk-25\bin\java.exe'
foreach ($stage in $Stages) {
    $index = @('policy-insertion', 'snapshot-insertion', 'bounded-early', 'flexible-final').IndexOf($stage)
    $provenance = $reference.sources[$index].provenance
    $artifact = if ($stage -eq 'flexible-final') { Join-Path $env:TEMP 'waterflex-scheduler-2902c87.jar' } else {
        Join-Path $repository ".scratch/ablation-$stage-90f407e/scheduler-service/target/scheduler-service-0.1.0-SNAPSHOT.jar"
    }
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $provenance.artifactSha256) { throw 'Frozen stage artifact differs from its recorded evidence' }
    $schema = 'benchmark_stage30_' + $stage.Replace('-', '_')
    $prefix = Join-Path $env:TEMP "waterflex-stage30-$stage"
    if (Test-Path -LiteralPath "$prefix.jsonl") { throw 'Benchmark evidence already exists' }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'Benchmark port is occupied' }
    $env:DATABASE_URL = "postgresql://waterflex:waterflex@127.0.0.1:5433/waterflex_test?schema=$schema&connection_limit=4"
    $env:JDBC_DATABASE_URL = "jdbc:postgresql://127.0.0.1:5433/waterflex_test?currentSchema=$schema"
    $env:DATABASE_USER = 'waterflex'; $env:DATABASE_PASSWORD = 'waterflex'
    $env:ROUTING_URL = 'http://127.0.0.1:18001'
    $env:ENGINE_URL = "http://127.0.0.1:$Port"
    $env:BENCHMARK_SERVER_MODE = 'current'
    $env:BENCHMARK_REVISION = $provenance.revision
    $env:BENCHMARK_ARTIFACT_SHA256 = $hash
    $env:BENCHMARK_VARIANT = "stage30-$stage"
    $env:BENCHMARK_REQUESTS = '30'; $env:BENCHMARK_SEED = '17'
    $env:BENCHMARK_SIZES = '20,30,50'
    $env:BENCHMARK_WORKLOADS = 'SPARSE,CLUSTERED,DISPERSED,MIXED_SKILL,TIGHT_WINDOW,ABSENCE,NEAR_CAPACITY'
    $env:BENCHMARK_CONCURRENCY = '1,5,10'; $env:BENCHMARK_CACHES = 'cold,warm'
    $env:BENCHMARK_OUTPUT = "$prefix.jsonl"
    $env:BENCHMARK_LOG_PATH = "$prefix-server.log"
    $env:BENCHMARK_PORTAL_URL = $null
    $env:BENCHMARK_ABLATION_MANIFEST = if ($stage -eq 'flexible-final') { $null } else {
        Join-Path $repository ".scratch/ablation-$stage-90f407e/ablation.json"
    }
    $bounded = if ($stage -in @('bounded-early', 'flexible-final')) { 'true' } else { 'false' }
    $arguments = @('-Xmx768m', '-jar', $artifact, "--server.port=$Port", '--spring.profiles.active=benchmark',
        '--booking.reservations.enabled=true', "--booking.search.bounded=$bounded",
        '--spring.datasource.hikari.maximum-pool-size=4', '--spring.datasource.hikari.minimum-idle=1')
    Push-Location (Join-Path $repository 'web')
    $server = $null
    try {
        & npx.cmd prisma migrate deploy *> "$prefix-migrate.log"
        if ($LASTEXITCODE -ne 0) { throw "Migration failed: $prefix-migrate.log" }
        $server = Start-Process $java -ArgumentList $arguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$prefix-server.log" -RedirectStandardError "$prefix-server.err"
        @{ stage = $stage; schema = $schema; port = $Port; artifact = $artifact; sha256 = $hash; arguments = $arguments;
            runnerRevision = (git rev-parse HEAD).Trim(); runnerSha256 = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash;
            pid = $server.Id; startedAt = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Depth 5 | Set-Content "$prefix-launch.json"
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            if ($server.HasExited) { throw 'Stage scheduler exited before startup' }
            try { $ready = (Invoke-WebRequest "$env:ENGINE_URL/health" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 } catch { $ready = $false }
            if ($ready) { break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $ready) { throw 'Stage scheduler startup timed out' }
        & npm.cmd run benchmark:scheduling *> "$prefix.log"
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; evidence retained at $prefix.log" }
        Write-Output "$stage complete: $prefix.jsonl"
    } finally {
        if ($null -ne $server -and -not $server.HasExited) { Stop-Process -InputObject $server }
        Pop-Location
    }
}
