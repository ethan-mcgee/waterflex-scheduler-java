# Move check timing study: how long a dispatcher waits between dropping a visit on another technician and seeing whether
# the move fits, and where that time goes. It drags on the portal's weekly schedule in a real browser, against a
# production build of the portal connected to the local rehearsal of the scheduler VM (deploy/vm with
# rehearsal.override.yml), and records each step: the browser, the portal (its Server-Timing phases), the hop to the
# scheduler, and the scheduler's own request log breakdown.
#
# Cases: the first check after the portal starts; warm drags (drive times cached) to a technician with room and to one
# whose day is full, at each simulated browser-to-portal delay; cold drags on days whose stops no cache has seen; and,
# with -Concurrency, several browsers dragging at once. The owner starts it; -DryRun prints the plan and changes nothing.
#
# Before the run:
#   - the rehearsal stack is up and healthy at -Url, with images built from the commit under test (deploy/vm/README.md,
#     Local rehearsal), and the development routing service it uses is up (-RoutingHealthUrl);
#   - $env:MOVE_TIMING_DATABASE_URL (or the variable named by -DatabaseUrlVariable) points at a portal database the
#     study may write to, with its own schema, for example ...@127.0.0.1:15432/waterflex_test?schema=move_timing. It is
#     read from the environment only, never from a file.
#   powershell -ExecutionPolicy Bypass -File infra/run-move-check-timing.ps1 -Stack 'VM rehearsal, Omaha routing CH' [-DryRun]
param(
    # What the scheduler under test is, recorded in the evidence (build, routing, shape).
    [Parameter(Mandatory)][string]$Stack,
    # The rehearsal proxy. The script issues the study's token (tenant move-timing) through the rehearsal's
    # scheduler-admin into its own environment and revokes it when it ends (study-token.psm1).
    [string]$Url = 'http://127.0.0.1:18080',
    [string]$RehearsalEnv = 'deploy/vm/rehearsal.env',
    [string]$RoutingHealthUrl = 'http://127.0.0.1:8001/health',
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')][string]$DatabaseUrlVariable = 'MOVE_TIMING_DATABASE_URL',
    [ValidateRange(1024, 65535)][int]$PortalPort = 13100,
    # The warm day, a weekday; the cold days are the weekdays after it. Empty: the Monday at least a week ahead.
    [string]$FirstDate = '',
    [ValidateRange(1, 1000)][int]$WarmReps = 30,
    [ValidateRange(0, 100)][int]$Warmup = 3,
    # Simulated browser-to-portal round trip delays in milliseconds, comma-separated.
    [string]$Delays = '0,20,50,100',
    # Cold days per outcome (feasible and not feasible), one drag each.
    [ValidateRange(0, 50)][int]$ColdDays = 10,
    # Browsers dragging at once for the optional concurrency case, comma-separated; empty skips it.
    [string]$Concurrency = '',
    [ValidateRange(1, 1000)][int]$ConcurrencyRounds = 10,
    [string]$RunsDirectory = 'experiments/runs',
    # Print the plan and its size, change nothing.
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'study-token.psm1') -Force
$repository = Split-Path $PSScriptRoot -Parent
$web = Join-Path $repository 'web'
$kit = Join-Path $repository 'infra/host-conformance'
$envFile = Join-Path $repository $RehearsalEnv
$compose = @('compose', '-f', (Join-Path $repository 'deploy/vm/docker-compose.yml'), '-f', (Join-Path $repository 'deploy/vm/rehearsal.override.yml'), '--env-file', $envFile)

$delayList = @($Delays -split ',' | Where-Object { $_.Trim() -ne '' } | ForEach-Object { [int]$_.Trim() })
$levels = @($Concurrency -split ',' | Where-Object { $_.Trim() -ne '' } | ForEach-Object { [int]$_.Trim() })
if ($FirstDate -eq '') {
    $day = (Get-Date).Date.AddDays(7)
    while ($day.DayOfWeek -ne [DayOfWeek]::Monday) { $day = $day.AddDays(1) }
    $FirstDate = $day.ToString('yyyy-MM-dd')
}
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$runId = "move-check-timing-$stamp"
$runDir = Join-Path (Join-Path $repository $RunsDirectory) $runId
$evidence = Join-Path $repository "research/evidence/move-check-timing-$stamp.json"
$concurrent = 0
foreach ($level in $levels) { $concurrent += $level * $ConcurrencyRounds }
$drags = 1 + $Warmup + $delayList.Count * $WarmReps * 2 + 2 * $ColdDays + $concurrent

Write-Host "Run $runId"
Write-Host "  warm day $FirstDate, $ColdDays cold days per outcome after it"
Write-Host "  first call 1, warm-up $Warmup (discarded), warm $WarmReps per target at delays $($delayList -join ', ') ms, cold $(2 * $ColdDays)"
Write-Host "  concurrency: $(if ($levels.Count -eq 0) { 'none' } else { "$($levels -join ', ') browsers, $ConcurrencyRounds rounds each" })"
Write-Host "  $drags drags; output $runDir; evidence $evidence"
if ($DryRun) { return }

if (-not (Test-Path -LiteralPath $envFile)) { throw "No rehearsal env file at $envFile (see deploy/vm/README.md, Local rehearsal)" }
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
$database = [Environment]::GetEnvironmentVariable($DatabaseUrlVariable)
if ([string]::IsNullOrWhiteSpace($database)) { throw "Set `$env:$DatabaseUrlVariable to the portal database the study may write to; it is never read from a file" }
try { $null = Invoke-WebRequest -UseBasicParsing -Uri $RoutingHealthUrl -TimeoutSec 5 }
catch { throw "The routing service at $RoutingHealthUrl is not answering: $($_.Exception.Message)" }
# The scheduler answers 503 while any metro's router is down, and the rehearsal may list metros this study does not
# use, so the components the study needs are checked by name: its database, its schema and the Omaha router.
$healthText = (& curl.exe -s -m 5 "$Url/health" | Out-String)
try { $health = $healthText | ConvertFrom-Json } catch { throw "The scheduler at $Url/health did not answer with its health: $healthText" }
foreach ($component in @('database', 'schema', 'routing:metro-omaha')) {
    $state = $health.components.$component.status
    if ($state -ne 'up') { throw "The scheduler's $component is $(if ($null -eq $state) { 'not reported' } else { $state }) at $Url/health" }
}

$dockerText = { param($arguments)
    $ErrorActionPreference = 'Continue'
    try { $output = & docker @arguments 2>&1 | ForEach-Object { "$_" }; $code = $LASTEXITCODE } finally { $ErrorActionPreference = 'Stop' }
    if ($code -ne 0) { throw "docker $($arguments -join ' ') failed`n$($output -join "`n")" }
    , @($output) }
$booking = @(& $dockerText ($compose + @('ps', '--format', '{{.Name}}', 'scheduler-booking')) | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
if ($booking.Count -eq 0) { throw 'No scheduler-booking container is running in the rehearsal stack' }

New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $evidence -Parent) | Out-Null
$log = Join-Path $runDir 'run.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$commit = (& git -C $repository rev-parse HEAD).Trim()
$dirty = -not [string]::IsNullOrWhiteSpace((& git -C $repository status --porcelain --untracked-files=no | Out-String))
$images = @{}
foreach ($name in $booking) { $images[$name] = (& $dockerText @('inspect', '--format', '{{.Image}}', $name) | Select-Object -First 1).Trim() }
$environment = [ordered]@{ runId = $runId; stack = $Stack; commit = $commit; dirty = $dirty; startedAt = [DateTime]::UtcNow.ToString('o');
    schedulerUrl = $Url; portalUrl = "http://127.0.0.1:$PortalPort"; bookingContainers = $images; firstDate = $FirstDate;
    warmReps = $WarmReps; warmup = $Warmup; delaysMs = $delayList; coldDays = $ColdDays; concurrency = $levels; concurrencyRounds = $ConcurrencyRounds;
    node = (& node --version).Trim() }
[IO.File]::WriteAllText((Join-Path $runDir 'environment.json'), ($environment | ConvertTo-Json -Depth 5), $utf8)

# Every step's output goes to run.log as UTF-8 without a byte order mark; Windows PowerShell's own redirection would
# write UTF-16. The token module writes its revocation output to token.log, kept apart for the same reason.
$append = { param([object[]]$Lines) [IO.File]::AppendAllLines($log, [string[]]@($Lines | ForEach-Object { "$_" }), $utf8) }
$run = { param([string]$Directory, [string]$File, [string[]]$Arguments, [string]$Step)
    Push-Location $Directory
    $ErrorActionPreference = 'Continue'
    try {
        & $append @("== $Step")
        $output = & $File @Arguments 2>&1; $code = $LASTEXITCODE
        & $append @($output)
    } finally { $ErrorActionPreference = 'Stop'; Pop-Location }
    if ($code -ne 0) { throw "$Step failed (see $log)" } }

$tokenId = $null
$portal = $null
$seeded = $false
$saved = @{ DATABASE_URL = $env:DATABASE_URL; SCHEDULER_API_URL = $env:SCHEDULER_API_URL }
try {
    $env:DATABASE_URL = $database
    $env:SCHEDULER_API_URL = $Url
    & $run $web 'npx.cmd' @('prisma', 'migrate', 'deploy') 'Portal migration'
    & $run $web 'npx.cmd' @('tsx', 'timing/seedMoveTiming.ts', 'seed', '--run-dir', $runDir, '--first-date', $FirstDate, '--cold-days', "$ColdDays") 'Seed'
    $seeded = $true
    $tokenId = New-StudyToken -Compose $compose -Tenant 'move-timing' -Name 'move-timing' -Label "move check timing $runId" -Variable 'SCHEDULER_API_TOKEN_MOVE_TIMING'
    & $run $web 'npm.cmd' @('run', 'build') 'Portal build'

    $portal = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run', 'start', '--', '-p', "$PortalPort", '-H', '127.0.0.1') -WorkingDirectory $web `
        -RedirectStandardOutput (Join-Path $runDir 'portal.log') -RedirectStandardError (Join-Path $runDir 'portal-errors.log') -PassThru -NoNewWindow
    $ready = $false
    for ($attempt = 0; $attempt -lt 60 -and -not $ready; $attempt++) {
        try { $null = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$PortalPort/" -TimeoutSec 5; $ready = $true } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw "The portal did not start on port $PortalPort (see $runDir/portal.log)" }

    $since = [DateTime]::UtcNow.AddSeconds(-1).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $env:TIMING_RUN_DIR = $runDir; $env:TIMING_PORTAL_URL = "http://127.0.0.1:$PortalPort"
    $env:TIMING_WARM_REPS = "$WarmReps"; $env:TIMING_WARMUP = "$Warmup"; $env:TIMING_DELAYS = ($delayList -join ',')
    $env:TIMING_CONCURRENCY = ($levels -join ','); $env:TIMING_CONCURRENCY_ROUNDS = "$ConcurrencyRounds"
    & $run $web 'npx.cmd' @('playwright', 'test', '-c', 'playwright.timing.config.ts') 'The drags'

    $schedulerLog = Join-Path $runDir 'scheduler.log'
    foreach ($name in $booking) {
        [IO.File]::AppendAllLines($schedulerLog, [string[]](& $dockerText @('logs', '--timestamps', '--since', $since, $name)), $utf8)
    }
    & $run $kit 'npx.cmd' @('tsx', 'move-check-report.ts', '--run-dir', $runDir, '--evidence', $evidence) 'The report'
    Write-Host "Report: $runDir/report.md"
} finally {
    if ($null -ne $portal) { $ErrorActionPreference = 'Continue'; & $append @(& taskkill.exe /PID $portal.Id /T /F 2>&1); $ErrorActionPreference = 'Stop' }
    if ($null -ne $tokenId) { Remove-StudyToken -Compose $compose -Id $tokenId -Variable 'SCHEDULER_API_TOKEN_MOVE_TIMING' -Log (Join-Path $runDir 'token.log') }
    if ($seeded) {
        try { & $run $web 'npx.cmd' @('tsx', 'timing/seedMoveTiming.ts', 'remove', '--run-dir', $runDir) 'Seed removal' }
        catch { Write-Warning "The seeded records were not removed: $($_.Exception.Message)" }
    }
    $env:DATABASE_URL = $saved.DATABASE_URL; $env:SCHEDULER_API_URL = $saved.SCHEDULER_API_URL
}
