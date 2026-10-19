# Dynamic booking horizon study: how SHED, SHORTENED and incompleteDates behave when each search's horizon follows the
# schedule instead of a fixed ten weekdays. Runs the load tool against a scheduler that is already running, then
# combines every report into one evidence file. Long (about 55 minutes with the defaults); the owner starts it.
#   powershell -ExecutionPolicy Bypass -File infra/run-horizon-study.ps1 -Url http://127.0.0.1:8000 -TokenFile .\conformance.token `
#     -MetricsUrl http://127.0.0.1:9000/actuator/prometheus -Stack 'local jars, SCHEDULER_SEARCH_CAPACITY=2, Omaha routing'
param(
    [Parameter(Mandatory)][string]$Url,
    [Parameter(Mandatory)][string]$TokenFile,
    # What the scheduler under test is, recorded in the evidence (build, capacity, routing).
    [Parameter(Mandatory)][string]$Stack,
    [string]$MetricsUrl = '',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    [ValidateRange(30, 600)][int]$DurationS = 120,
    [ValidateRange(1, 5)][int]$Repeats = 3,
    # Seeds are this base + the run's index (the load tool takes 0 to 1000, and five repeats make 40 runs), so no two
    # runs, or two studies with bases at least 40 apart, share an address.
    [ValidateRange(0, 960)][int]$SeedBase = 400
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$kit = Join-Path $repository 'infra/host-conformance'
$stamp = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$evidence = Join-Path $repository "docs/evidence/api-load-$stamp-dynamic-horizon.json"
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository ".scratch/horizon-study-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmss'))"
New-Item -ItemType Directory -Force $runs | Out-Null
$token = (Resolve-Path -LiteralPath $TokenFile).Path
try { Invoke-WebRequest "$Url/health" -UseBasicParsing -TimeoutSec 5 | Out-Null } catch { throw "The scheduler at $Url is not answering /health" }

# Low volume: plenty of technicians, one visit a day, nothing booked out. High demand: few technicians with the first
# twelve weekdays booked out (four visits span 86 percent of the kit's 08:00 to 17:00 shift).
$profiles = @(
    @{ name = 'low'; args = @('--technicians', '6', '--appointments', '1', '--booked-weekdays', '0') },
    @{ name = 'high'; args = @('--technicians', '3', '--appointments', '2', '--booked-weekdays', '12', '--booked-appointments', '4') }
)
$modes = @(
    @{ name = 'fixed10'; args = @('--horizon', 'fixed', '--horizon-weekdays', '10') },
    @{ name = 'dynamic'; args = @('--horizon', 'dynamic', '--horizon-weekdays', '15', '--lead-weekdays', '2', '--full-percent', '85') }
)
$concurrency = @(1, 5)

$index = 0
$failures = @()
Push-Location $kit
try {
    # Interleaved, so a slow drift in the stack shows in every cell rather than in one.
    for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        foreach ($desks in $concurrency) { foreach ($profile in $profiles) { foreach ($mode in $modes) {
            $index++
            $seed = $SeedBase + $index
            $label = "$($profile.name) $($mode.name) c$desks seed $seed"
            Write-Output "[$index] $label"
            $arguments = @('tsx', 'load.ts', '--url', $Url, '--token-file', $token, '--metro', $Metro, '--time-zone', $TimeZone,
                '--area', $Area, '--scenario', 'booking', '--concurrency', "$desks", '--duration-s', "$DurationS", '--seed', "$seed",
                '--label', $label, '--out', $runs) + $profile.args + $mode.args
            if ($MetricsUrl -ne '') { $arguments += @('--metrics-url', $MetricsUrl) }
            & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
            # Exit 1 is a run that finished with contract breaches or host anomalies: kept, and listed in the evidence.
            if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
        } } }
    }
} finally { Pop-Location }

$reports = Get-ChildItem -LiteralPath $runs -Filter 'load-booking-*.json' | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json }
if ($reports.Count -ne $index) { throw "Expected $index reports, found $($reports.Count) in $runs" }
# A run whose every search failed (routing unreachable, a wrong token for the routing service) measured nothing.
$unanswered = @($reports | Where-Object { $_.booking.searches -eq $_.booking.failedSearches } | ForEach-Object { $_.label })
if ($unanswered.Count -gt 0) { throw "No search was answered in: $($unanswered -join '; ') (see the run logs in $runs)" }
[ordered]@{
    study = 'Dynamic booking horizon: refinement outcomes, incompleteDates and latency by horizon length'
    scheduler = $Stack
    protocol = "$DurationS s cold-address booking runs, one fresh seed each. Profiles: low (6 technicians, 1 visit a day) and high (3 technicians, 2 visits a day, the first 12 weekdays at 4). Modes: fixed 10 weekdays, and dynamic (2 weekdays past the furthest weekday at least 85 percent full, at most 15). 1 and 5 desks, $Repeats repeats, interleaved."
    hypotheses = @(
        'Low volume, dynamic: 3 to 4 dates, so fewer distinct windows; more searches at or below regularWindowThreshold make the refinement required, so fewer SHED and lower latency, possibly fewer offers.',
        'High demand, dynamic: about 19 to 21 dates, so a longer insertion pass; more DEADLINE SHORTENED and more incompleteDates, but offers on days the fixed horizon never reached.'
    )
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    runsWithDefects = $failures
    runs = $reports
} | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
