# Window threshold study: how regularWindowThreshold changes the offers a search returns (quality) and what it costs
# under load (efficiency). Runs against a scheduler that is already running, then combines every report into one
# evidence file. Long (about 75 minutes with the defaults); the owner starts it.
#   powershell -ExecutionPolicy Bypass -File infra/run-threshold-study.ps1 -Url http://127.0.0.1:8000 -TokenFile .scratch/loadtest.token `
#     -MetricsUrl http://127.0.0.1:9000/actuator/prometheus -Stack 'local compose, Omaha routing'
param(
    [Parameter(Mandatory)][string]$Url,
    [Parameter(Mandatory)][string]$TokenFile,
    # What the scheduler under test is, recorded in the evidence (build, capacity, routing).
    [Parameter(Mandatory)][string]$Stack,
    [string]$MetricsUrl = '',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    # Quality: every threshold searched on the same cases; the first is the baseline the others are compared with.
    [string]$QualityThresholds = '2,10,25,50,1000',
    [ValidateRange(1, 200)][int]$Cases = 20,
    [ValidateRange(1, 5)][int]$Schedules = 2,
    # Efficiency: load runs per threshold. Comma-separated, as -File passes every value as text.
    [string]$LoadThresholds = '2,25,1000',
    [ValidateRange(30, 600)][int]$DurationS = 120,
    [ValidateRange(1, 5)][int]$Repeats = 2,
    # Seeds are this base + the run's index (the tools take 0 to 1000), so no two runs, or two studies with bases at
    # least 100 apart, share an address.
    [ValidateRange(0, 900)][int]$SeedBase = 100
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$kit = Join-Path $repository 'infra/host-conformance'
$stamp = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$evidence = Join-Path $repository "docs/evidence/api-load-$stamp-window-threshold.json"
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository ".scratch/threshold-study-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmss'))"
New-Item -ItemType Directory -Force $runs | Out-Null
$token = (Resolve-Path -LiteralPath $TokenFile).Path
try { Invoke-WebRequest "$Url/health" -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { throw "The scheduler at $Url is not answering /health" }
$loadThresholdList = @($LoadThresholds -split ',' | ForEach-Object { [int]$_.Trim() })
if (@($loadThresholdList | Where-Object { $_ -lt 0 -or $_ -gt 1000 }).Count -gt 0) { throw '-LoadThresholds are whole numbers from 0 to 1000' }
$loadRuns = $loadThresholdList.Count * 2 * 2 * $Repeats
if ($Schedules * 2 + $loadRuns -gt 1000 - $SeedBase) { throw 'Too many runs for the seed range; lower -SeedBase' }

# Low volume: plenty of technicians, one visit a day. High demand: few technicians with the first twelve weekdays
# booked out (four visits held to their hours span 86 percent of the kit's 08:00 to 17:00 shift). Both search a fixed
# ten weekdays, the portal's default, so the threshold is the only change between cells.
$profiles = @(
    @{ name = 'low'; args = @('--technicians', '6', '--appointments', '1', '--booked-weekdays', '0') },
    @{ name = 'high'; args = @('--technicians', '3', '--appointments', '2', '--booked-weekdays', '12', '--booked-appointments', '4') }
)
$common = @('--url', $Url, '--token-file', $token, '--metro', $Metro, '--time-zone', $TimeZone, '--area', $Area, '--horizon-weekdays', '10')
$index = 0
$failures = @()
Push-Location $kit
try {
    foreach ($profile in $profiles) { for ($schedule = 1; $schedule -le $Schedules; $schedule++) {
        $index++
        $seed = $SeedBase + $index
        $label = "quality $($profile.name) schedule $schedule seed $seed"
        Write-Output "[$index] $label"
        $arguments = @('tsx', 'quality.ts') + $common + @('--thresholds', $QualityThresholds, '--cases', "$Cases", '--seed', "$seed",
            '--label', $label, '--out', (Join-Path $runs 'quality')) + $profile.args
        & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
        if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
    } }
    # Interleaved, so a slow drift in the stack shows in every cell rather than in one.
    for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        foreach ($desks in @(1, 5)) { foreach ($profile in $profiles) { foreach ($threshold in $loadThresholdList) {
            $index++
            $seed = $SeedBase + $index
            $label = "load $($profile.name) threshold $threshold c$desks seed $seed"
            Write-Output "[$index] $label"
            $arguments = @('tsx', 'load.ts') + $common + @('--scenario', 'booking', '--concurrency', "$desks", '--duration-s', "$DurationS",
                '--window-threshold', "$threshold", '--seed', "$seed", '--label', $label, '--out', (Join-Path $runs 'load')) + $profile.args
            if ($MetricsUrl -ne '') { $arguments += @('--metrics-url', $MetricsUrl) }
            & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
            # Exit 1 is a run that finished with contract breaches or host anomalies: kept, and listed in the evidence.
            if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
        } } }
    }
} finally { Pop-Location }

$read = { param($directory, $filter) Get-ChildItem -LiteralPath (Join-Path $runs $directory) -Filter $filter | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json } }
$quality = @(& $read 'quality' 'quality-*.json')
$load = @(& $read 'load' 'load-booking-*.json')
if ($quality.Count -ne $Schedules * 2) { throw "Expected $($Schedules * 2) quality reports, found $($quality.Count) in $runs" }
if ($load.Count -ne $loadRuns) { throw "Expected $loadRuns load reports, found $($load.Count) in $runs" }
# A run whose every search failed (routing unreachable, a wrong token for the routing service) measured nothing.
$unanswered = @($load | Where-Object { $_.booking.searches -eq $_.booking.failedSearches } | ForEach-Object { $_.label }) +
    @($quality | Where-Object { @($_.cases | ForEach-Object { $_.searched } | Where-Object { $_.status -eq 201 }).Count -eq 0 } | ForEach-Object { $_.label })
if ($unanswered.Count -gt 0) { throw "No search was answered in: $($unanswered -join '; ') (see the run logs in $runs)" }
[ordered]@{
    study = 'Window threshold: offer quality on the same cases, and search efficiency under load'
    scheduler = $Stack
    protocol = "Quality: per profile, $Schedules schedules optimized with daily proposals, each with $Cases job addresses searched once per threshold ($QualityThresholds) in a rotated order; the best offer confirmed and priced with routes/evaluate, never written. Efficiency: $DurationS s cold-address booking runs at thresholds $($loadThresholdList -join ', '), 1 and 5 desks, $Repeats repeats, interleaved. Profiles: low (6 technicians, 1 visit a day) and high (3 technicians, 2 visits a day, the first 12 weekdays at 4). Fixed 10 weekdays throughout."
    hypotheses = @(
        'Quality, low volume: windows are plentiful, so a required refinement rarely changes the offers or the best offer''s cost.',
        'Quality, high demand: booked-out days need visits moved, so a high threshold finds earlier or cheaper best offers on some cases; a 6-case probe found the same offers on most.',
        'Efficiency, 1 desk: the refinement runs to completion instead of the 250 ms cap, so the search median rises (probe: about 0.3 to 0.6 s), with no BUSY answers.',
        'Efficiency, 5 desks: a required refinement is never shed, so searches queue behind each other; throughput falls and latency rises steeply at 1000 (a 20 s probe: 72 against 583 searches a minute), intermediate thresholds in between.'
    )
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    runsWithDefects = $failures
    quality = $quality
    runs = $load
} | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
