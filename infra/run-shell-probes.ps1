# Service shell probes. Each runs against a scheduler that is already running and writes one evidence file; the owner
# starts them.
#
# db-stall: booking load at 1 and 5 desks while the scheduler's database is paused for -PauseMs every -IntervalS
# seconds (one arm per pause length; 0 is the baseline). Answers whether the Hikari connect and validation timeouts
# (250 ms each) turn a short database stall into 429 BUSY or 503 answers, and how many. About 12 minutes.
#   powershell -ExecutionPolicy Bypass -File infra/run-shell-probes.ps1 -Probe db-stall -Url http://127.0.0.1:8000 `
#     -TokenFile .scratch/loadtest.token -Stack 'local compose, Omaha routing' -Database waterflex-scheduler-java-scheduler-db-1
#
# daily-thread: two queued daily proposals with long searches (both background lanes of a daily replica busy) while
# /health is read every second. Answers whether CPU-bound solves starve the replica's virtual threads (health checks,
# cancel watchers): a slow or 503 /health while both solves run. Run it on the build before and after the platform
# thread change, on a daily replica limited to 2 CPUs with background limit 2 and 2 queue workers
# (SCHEDULER_SEARCH_BACKGROUNDLIMIT=2, SCHEDULER_DAILY_QUEUE_WORKERS=2; one worker runs one solve at a time). About 8 minutes.
#   powershell -ExecutionPolicy Bypass -File infra/run-shell-probes.ps1 -Probe daily-thread -Url http://127.0.0.1:8000 `
#     -TokenFile .scratch/loadtest.token -Stack 'daily replica, 2 CPUs, background limit 2, build <sha>' -Name platform-thread
param(
    [Parameter(Mandatory)][ValidateSet('db-stall', 'daily-thread')][string]$Probe,
    [Parameter(Mandatory)][string]$Url,
    [Parameter(Mandatory)][string]$TokenFile,
    # What the scheduler under test is, recorded in the evidence (build, capacity, CPUs, routing).
    [Parameter(Mandatory)][string]$Stack,
    [string]$MetricsUrl = '',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    # db-stall: the database container to pause, pause lengths (comma-separated, 0 for the baseline) and how often.
    [string]$Database = 'waterflex-scheduler-java-scheduler-db-1',
    [string]$PauseMs = '0,300,1000',
    [ValidateRange(2, 60)][int]$IntervalS = 10,
    [ValidateRange(30, 600)][int]$DurationS = 120,
    # daily-thread: each proposal's cost search in seconds (both run at once, so the probe lasts about this long twice).
    [ValidateRange(30, 240)][int]$SearchS = 120,
    [ValidateRange(0, 900)][int]$SeedBase = 300,
    # Appended to the evidence file name, so a before and an after run of one probe on one day keep apart.
    [ValidatePattern('^[a-z0-9-]*$')][string]$Name = ''
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$kit = Join-Path $repository 'infra/host-conformance'
$stamp = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$suffix = if ($Name -ne '') { "-$Name" } else { '' }
$evidence = Join-Path $repository "docs/evidence/shell-probe-$stamp-$Probe$suffix.json"
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository "experiments/runs/shell-probe-$Probe-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmss'))"
New-Item -ItemType Directory -Force $runs | Out-Null
$token = (Resolve-Path -LiteralPath $TokenFile).Path
try { Invoke-WebRequest "$Url/health" -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { throw "The scheduler at $Url is not answering /health" }
$common = @('--url', $Url, '--token-file', $token, '--metro', $Metro, '--time-zone', $TimeZone, '--area', $Area)
if ($MetricsUrl -ne '') { $common += @('--metrics-url', $MetricsUrl) }

# Reads /health once a second until the stop file appears; one line per read: elapsed ms, status, components down.
$healthWatch = {
    param($url, $stop, $out)
    while (-not (Test-Path -LiteralPath $stop)) {
        $started = [Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-WebRequest "$url/health" -UseBasicParsing -TimeoutSec 10
            $status = [int]$response.StatusCode; $body = $response.Content
        } catch {
            $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            $body = ''
        }
        $down = if ($body -ne '') { (($body | ConvertFrom-Json).components.PSObject.Properties | Where-Object { $_.Value.status -ne 'up' } | ForEach-Object { $_.Name }) -join ' ' } else { '' }
        [ordered]@{ at = [DateTime]::UtcNow.ToString('o'); elapsedMs = $started.ElapsedMilliseconds; status = $status; down = $down } |
            ConvertTo-Json -Compress | Add-Content -LiteralPath $out
        Start-Sleep -Milliseconds ([Math]::Max(0, 1000 - $started.ElapsedMilliseconds))
    }
}

$index = 0
$failures = @()
Push-Location $kit
try {
    if ($Probe -eq 'db-stall') {
        docker inspect $Database *> $null
        if ($LASTEXITCODE -ne 0) { throw "No container named $Database" }
        $pauses = @($PauseMs -split ',' | ForEach-Object { [int]$_.Trim() })
        if (@($pauses | Where-Object { $_ -lt 0 -or $_ -gt 5000 }).Count -gt 0) { throw '-PauseMs are whole numbers from 0 to 5000' }
        foreach ($desks in @(1, 5)) { foreach ($pause in $pauses) {
            $index++
            $seed = $SeedBase + $index
            $label = "db-stall pause $pause ms every $IntervalS s c$desks seed $seed"
            Write-Output "[$index] $label"
            $stop = Join-Path $runs "stop-$index"
            # Pauses the database on its own schedule while the load runs; always leaves it running.
            $staller = Start-Job -ArgumentList $Database, $pause, $IntervalS, $stop -ScriptBlock {
                param($database, $pause, $interval, $stop)
                $log = @()
                while (-not (Test-Path -LiteralPath $stop)) {
                    Start-Sleep -Seconds $interval
                    if ($pause -eq 0 -or (Test-Path -LiteralPath $stop)) { continue }
                    docker pause $database *> $null
                    try { Start-Sleep -Milliseconds $pause } finally { docker unpause $database *> $null }
                    $log += [DateTime]::UtcNow.ToString('o')
                }
                $log
            }
            try {
                $arguments = @('tsx', 'load.ts') + $common + @('--scenario', 'booking', '--concurrency', "$desks", '--duration-s', "$DurationS",
                    '--seed', "$seed", '--label', $label, '--out', (Join-Path $runs 'load'))
                & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
                if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
            } finally {
                New-Item -ItemType File -Force $stop | Out-Null
                Wait-Job $staller | Out-Null
                $stalls = @(Receive-Job $staller)
                Remove-Job $staller -Force
                # Never leave the database paused; unpausing a running container is an error, so ask first.
                if ((docker inspect -f '{{.State.Paused}}' $Database) -eq 'true') { docker unpause $Database | Out-Null }
                @{ label = $label; stalls = $stalls } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runs "stalls-$index.json") -Encoding utf8
            }
        } }
    } else {
        $index = 1
        $label = "daily-thread 2 queued proposals, $SearchS s search each"
        Write-Output "[1] $label"
        $stop = Join-Path $runs 'stop-1'
        $health = Join-Path $runs 'health.jsonl'
        $watcher = Start-Job -ScriptBlock $healthWatch -ArgumentList $Url, $stop, $health
        try {
            # Two planners queue a day each with a long search, then poll it; the run lasts long enough for both to finish.
            $arguments = @('tsx', 'load.ts') + $common + @('--scenario', 'daily', '--daily-mode', 'async', '--concurrency', '2',
                '--cost-seconds', "$SearchS", '--fairness-seconds', '0', '--duration-s', "$($SearchS * 2 + 30)",
                '--seed', "$($SeedBase + 1)", '--label', $label, '--out', (Join-Path $runs 'load'))
            & npx.cmd @arguments *> (Join-Path $runs 'run-1.log')
            if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-1.log)" }
        } finally {
            New-Item -ItemType File -Force $stop | Out-Null
            Wait-Job $watcher | Out-Null
            Receive-Job $watcher | Out-Null
            Remove-Job $watcher -Force
        }
    }
} finally { Pop-Location }

$load = @(Get-ChildItem -LiteralPath (Join-Path $runs 'load') -Filter 'load-*.json' | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json })
if ($load.Count -ne $index) { throw "Expected $index load reports, found $($load.Count) in $runs" }
$body = [ordered]@{
    probe = $Probe
    scheduler = $Stack
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    runsWithDefects = $failures
    runs = $load
}
if ($Probe -eq 'db-stall') {
    $body.protocol = "Booking load, cold addresses, $DurationS s at 1 and 5 desks; the database container $Database paused for $PauseMs ms every $IntervalS s, one arm per length (0 is the baseline)."
    $body.hypothesis = 'A pause shorter than the Hikari connection-timeout (250 ms) costs latency only; a longer one turns searches that need a connection during it into 429 BUSY (refused at admission or at publish) without 500s.'
    $body.stalls = @(Get-ChildItem -LiteralPath $runs -Filter 'stalls-*.json' | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json })
} else {
    $body.protocol = "Two daily proposals queued at once (Prefer: respond-async), $SearchS s cost search each, no fairness phase; /health read once a second meanwhile."
    $body.hypothesis = 'With the solve on a virtual thread and both carriers of a 2 CPU replica pinned, /health slows past its 1 s component checks or answers 503 while both solves run; on a platform thread it stays fast.'
    $body.health = @(Get-Content -LiteralPath (Join-Path $runs 'health.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
}
$body | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
