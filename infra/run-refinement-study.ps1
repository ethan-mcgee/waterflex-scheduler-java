# Refinement cap study, in two parts. Part "cap": at threshold 2, how much of threshold 1000's offer quality a longer
# optional refinement (bookingRefinementMs) recovers, and what it adds to one search, at small fleets. Part
# "capacity": several dealerships booking at once, each its own tenant with one desk, against a scheduler restarted
# with 2 and then 5 search slots on as many CPUs; it shows how far slots keep refinements from being shed and searches
# from queueing. Runs against the local compose stack, restores it when done, and combines every report into one
# evidence file. Long (about 3.5 hours with the defaults); the owner starts it.
#   powershell -ExecutionPolicy Bypass -File infra/run-refinement-study.ps1 -Url http://127.0.0.1:8000 -TokenFile .scratch/loadtest.token `
#     -MetricsUrl http://127.0.0.1:9000/actuator/prometheus -Stack 'local compose, Omaha routing'
param(
    [Parameter(Mandatory)][string]$Url,
    # Part cap's tenant. Part capacity issues one token per client (tenants loadtest-client-<n>) on first use and keeps
    # them in -ClientTokenDirectory; revoke them with scheduler-admin --command=revoke-token <id> (ids in the evidence).
    [Parameter(Mandatory)][string]$TokenFile,
    # What the scheduler under test is, recorded in the evidence (build, routing).
    [Parameter(Mandatory)][string]$Stack,
    [string]$MetricsUrl = '',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    # Comma-separated, as -File passes every value as text: 'cap', 'capacity' or both.
    [string]$Parts = 'cap,capacity',
    # Variants are threshold[:refinement ms]; the first quality variant is the baseline the others are compared with.
    [string]$QualityVariants = '2:250,2:500,2:1000,1000',
    [ValidateRange(1, 200)][int]$Cases = 20,
    [ValidateRange(1, 5)][int]$Schedules = 2,
    [string]$CapFleets = '5,10,15',
    [string]$CapLoadVariants = '2:250,2:500,2:1000,1000',
    [string]$CapacityVariants = '2:250,2:1000,1000',
    [string]$CapacityFleets = '5,10',
    [string]$Clients = '5,10',
    # Search slots, each restart giving the scheduler as many CPUs.
    [string]$Capacities = '2,5',
    [ValidateRange(30, 600)][int]$DurationS = 120,
    # Seeds are this base + a running index (the tools take 0 to 1000); every client of a run has its own.
    [ValidateRange(0, 900)][int]$SeedBase = 300,
    [string]$ClientTokenDirectory = '.scratch/refinement-clients',
    # Names the evidence file, api-load-<date>-<Study>.json.
    [ValidatePattern('^[a-z0-9-]+$')][string]$Study = 'refinement-cap'
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$kit = Join-Path $repository 'infra/host-conformance'
$compose = Join-Path $repository 'docker-compose.yml'
$stamp = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$evidence = Join-Path $repository "research/evidence/api-load-$stamp-$Study.json"
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository "experiments/runs/refinement-study-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmss'))"
New-Item -ItemType Directory -Force $runs | Out-Null
$token = (Resolve-Path -LiteralPath $TokenFile).Path

$list = { param($text, $name, $min, $max)
    $items = @($text -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ })
    if ($items.Count -eq 0 -or @($items | Where-Object { $_ -lt $min -or $_ -gt $max }).Count -gt 0) { throw "-$name are whole numbers from $min to $max" }
    , $items }
$variants = { param($text, $name)
    $items = @($text -split ',' | ForEach-Object { $_.Trim() })
    if ($items.Count -eq 0 -or @($items | Where-Object { $_ -notmatch '^\d{1,4}(:\d{1,4})?$' }).Count -gt 0) { throw "-$name are threshold[:refinement ms]" }
    , $items }
# The load tool's options for a variant: 2:500 is --window-threshold 2 --refinement-ms 500.
$variantArgs = { param($variant)
    $parts = $variant -split ':'
    $arguments = @('--window-threshold', $parts[0])
    if ($parts.Count -gt 1) { $arguments += @('--refinement-ms', $parts[1]) }
    , $arguments }
$partList = @($Parts -split ',' | ForEach-Object { $_.Trim() })
if ($partList.Count -eq 0 -or @($partList | Where-Object { $_ -ne 'cap' -and $_ -ne 'capacity' }).Count -gt 0) { throw '-Parts lists cap, capacity or both' }
$doCap = $partList -contains 'cap'
$doCapacity = $partList -contains 'capacity'
$capFleetList = & $list $CapFleets 'CapFleets' 1 200
$capacityFleetList = & $list $CapacityFleets 'CapacityFleets' 1 200
$clientList = & $list $Clients 'Clients' 1 20
$capacityList = & $list $Capacities 'Capacities' 1 16
$capLoadList = & $variants $CapLoadVariants 'CapLoadVariants'
$capacityVariantList = & $variants $CapacityVariants 'CapacityVariants'
& $variants $QualityVariants 'QualityVariants' | Out-Null
$qualityRuns = if ($doCap) { $Schedules * 2 * $capFleetList.Count } else { 0 }
$capLoadRuns = if ($doCap) { $capLoadList.Count * 2 * $capFleetList.Count } else { 0 }
$clientsPerCapacity = ($clientList | Measure-Object -Sum).Sum * $capacityVariantList.Count * 2 * $capacityFleetList.Count
$capacityReports = if ($doCapacity) { $clientsPerCapacity * $capacityList.Count } else { 0 }
if ($qualityRuns + $capLoadRuns + $capacityReports -gt 1000 - $SeedBase) { throw 'Too many runs for the seed range; lower -SeedBase or the matrix' }

$health = { param($seconds)
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        try { if ((Invoke-WebRequest "$Url/health" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { return $true } } catch { }
        Start-Sleep -Seconds 2
    }
    $false }
if (-not (& $health 10)) { throw "The scheduler at $Url is not answering /health" }

# Low volume: one visit a day per technician. High demand: two, with the first twelve weekdays booked out (four
# visits held to their hours span 86 percent of the kit's 08:00 to 17:00 shift). Both search a fixed ten weekdays.
$profiles = @(
    @{ name = 'low'; args = @('--appointments', '1', '--booked-weekdays', '0') },
    @{ name = 'high'; args = @('--appointments', '2', '--booked-weekdays', '12', '--booked-appointments', '4') }
)
$common = @('--url', $Url, '--metro', $Metro, '--time-zone', $TimeZone, '--area', $Area, '--horizon-weekdays', '10')
$index = 0
$failures = @()
$capacitySettings = @()
$clientTokens = @()
$restarted = $false
# Start-Process in Windows PowerShell joins its arguments with spaces and quotes none of them.
$quoted = { param($arguments) , @($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) }

# One token per client, so each simulated dealership is its own tenant: a booking search replaces its tenant's earlier
# booking state for the metro and date, so desks sharing a tenant end each other's offers.
$clientToken = { param($n)
    $directory = Join-Path $repository $ClientTokenDirectory
    New-Item -ItemType Directory -Force $directory | Out-Null
    $file = Join-Path $directory "client-$n.token"
    if (-not (Test-Path -LiteralPath $file)) {
        # Docker writes progress to stderr, which must not stop the script.
        $ErrorActionPreference = 'Continue'
        $output = docker compose -f $compose run --rm scheduler-admin --command=issue-token "loadtest-client-$n" "Load test client $n" 'refinement capacity study' 2>&1 | Out-String
        $id = [regex]::Match($output, '(?m)^id: (\S+)').Groups[1].Value
        $value = [regex]::Match($output, '(?m)^token: (wfs_\S+)').Groups[1].Value
        if ($id -eq '' -or $value -eq '') { throw "Could not issue a token for client $n`n$output" }
        Set-Content -LiteralPath $file -Value $value -Encoding ascii -NoNewline
        Set-Content -LiteralPath "$file.id" -Value $id -Encoding ascii -NoNewline
    }
    $script:clientTokens += [ordered]@{ client = $n; tenant = "loadtest-client-$n"; tokenId = (Get-Content -LiteralPath "$file.id" -Raw).Trim() }
    $file }

# Restarts the scheduler with the given search slots on as many CPUs; the image and every other setting are unchanged.
$setCapacity = { param($capacity)
    $override = Join-Path $runs "capacity-$capacity.yml"
    @(
        'services:', '  scheduler-service:', "    cpus: $capacity", '    environment:', "      SCHEDULER_SEARCH_CAPACITY: `"$capacity`""
    ) | Set-Content -LiteralPath $override -Encoding ascii
    $ErrorActionPreference = 'Continue'
    docker compose -f $compose -f $override up -d --no-deps --no-build scheduler-service *> (Join-Path $runs "restart-$capacity.log")
    if ($LASTEXITCODE -ne 0) { throw "Restarting the scheduler with capacity $capacity failed (see restart-$capacity.log)" }
    $script:restarted = $true
    if (-not (& $health 180)) { throw "The scheduler did not come back with capacity $capacity" }
    Start-Sleep -Seconds 5
    $container = (docker compose -f $compose ps -q scheduler-service).Trim()
    $inspect = docker inspect $container | ConvertFrom-Json
    $configured = @($inspect[0].Config.Env | Where-Object { $_ -like 'SCHEDULER_SEARCH_CAPACITY=*' })
    if ($configured -notcontains "SCHEDULER_SEARCH_CAPACITY=$capacity" -or $inspect[0].HostConfig.NanoCpus -ne [long]$capacity * 1000000000) {
        throw "The restarted scheduler does not have capacity $capacity on $capacity CPUs"
    }
    $script:capacitySettings += [ordered]@{ capacity = $capacity; nanoCpus = $inspect[0].HostConfig.NanoCpus; environment = $configured }
}

Push-Location $kit
try {
    if ($doCap) {
        foreach ($fleet in $capFleetList) { foreach ($profile in $profiles) { for ($schedule = 1; $schedule -le $Schedules; $schedule++) {
            $index++
            $seed = $SeedBase + $index
            $label = "quality $($profile.name) $fleet technicians schedule $schedule seed $seed"
            Write-Output "[$index] $label"
            $arguments = @('tsx', 'quality.ts') + $common + @('--token-file', $token, '--variants', $QualityVariants, '--cases', "$Cases",
                '--seed', "$seed", '--label', $label, '--out', (Join-Path $runs 'quality'), '--technicians', "$fleet") + $profile.args
            & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
            if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
        } } }
        # One desk: the time a customer waits for one search, with nothing queued ahead of it.
        foreach ($fleet in $capFleetList) { foreach ($profile in $profiles) { foreach ($variant in $capLoadList) {
            $index++
            $seed = $SeedBase + $index
            $label = "cap $($profile.name) $fleet technicians variant $variant c1 seed $seed"
            Write-Output "[$index] $label"
            $arguments = @('tsx', 'load.ts') + $common + @('--token-file', $token, '--scenario', 'booking', '--concurrency', '1',
                '--duration-s', "$DurationS", '--seed', "$seed", '--label', $label, '--out', (Join-Path $runs 'cap'), '--technicians', "$fleet") +
                (& $variantArgs $variant) + $profile.args
            if ($MetricsUrl -ne '') { $arguments += @('--metrics-url', $MetricsUrl) }
            & npx.cmd @arguments *> (Join-Path $runs "run-$index.log")
            # Exit 1 is a run that finished with contract breaches or host anomalies: kept, and listed in the evidence.
            if ($LASTEXITCODE -eq 1) { $failures += $label } elseif ($LASTEXITCODE -ne 0) { throw "Run failed: $label (see run-$index.log)" }
        } } }
    }
    if ($doCapacity) {
        $maxClients = ($clientList | Measure-Object -Maximum).Maximum
        $tokens = @(for ($n = 1; $n -le $maxClients; $n++) { & $clientToken $n })
        foreach ($capacity in $capacityList) {
            & $setCapacity $capacity
            # Interleaved within a capacity, so a slow drift in the stack shows in every cell rather than in one.
            foreach ($count in $clientList) { foreach ($fleet in $capacityFleetList) { foreach ($profile in $profiles) { foreach ($variant in $capacityVariantList) {
                $cell = "capacity $capacity clients $count $($profile.name) $fleet technicians variant $variant"
                Write-Output "[$($index + 1)..$($index + $count)] $cell"
                $processes = @()
                for ($client = 1; $client -le $count; $client++) {
                    $index++
                    $seed = $SeedBase + $index
                    $label = "$cell client $client of $count seed $seed"
                    $arguments = @('tsx', 'load.ts') + $common + @('--token-file', $tokens[$client - 1], '--scenario', 'booking', '--concurrency', '1',
                        '--duration-s', "$DurationS", '--seed', "$seed", '--label', $label, '--out', (Join-Path $runs "capacity/run-$index"), '--technicians', "$fleet") +
                        (& $variantArgs $variant) + $profile.args
                    # The scheduler's metrics are the whole process's, so one client samples them for the cell.
                    if ($client -eq 1 -and $MetricsUrl -ne '') { $arguments += @('--metrics-url', $MetricsUrl) }
                    $process = Start-Process -FilePath 'npx.cmd' -ArgumentList (& $quoted $arguments) -NoNewWindow -PassThru `
                        -RedirectStandardOutput (Join-Path $runs "run-$index.log") -RedirectStandardError (Join-Path $runs "run-$index.err.log")
                    # Windows PowerShell reports a started process's exit code only if its handle was read while it ran.
                    $null = $process.Handle
                    $processes += @{ process = $process; label = $label; index = $index }
                }
                foreach ($entry in $processes) {
                    $entry.process.WaitForExit()
                    $code = $entry.process.ExitCode
                    if ($code -eq 1) { $failures += $entry.label } elseif ($code -ne 0) { throw "Run failed: $($entry.label) (see run-$($entry.index).log)" }
                }
            } } } }
        }
    }
} finally {
    Pop-Location
    if ($restarted) {
        # Back to the compose file's own settings.
        $ErrorActionPreference = 'Continue'
        docker compose -f $compose up -d --no-deps --no-build scheduler-service *> (Join-Path $runs 'restore.log')
        if (-not (& $health 180)) { Write-Warning "The scheduler did not come back after restoring its settings (see $runs/restore.log)" }
        $ErrorActionPreference = 'Stop'
    }
}

$read = { param($directory, $filter)
    $path = Join-Path $runs $directory
    if (-not (Test-Path -LiteralPath $path)) { return , @() }
    , @(Get-ChildItem -LiteralPath $path -Filter $filter -Recurse | Sort-Object Name | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json }) }
$quality = & $read 'quality' 'quality-*.json'
$cap = & $read 'cap' 'load-booking-*.json'
$capacityRuns = & $read 'capacity' 'load-booking-*.json'
if ($quality.Count -ne $qualityRuns) { throw "Expected $qualityRuns quality reports, found $($quality.Count) in $runs" }
if ($cap.Count -ne $capLoadRuns) { throw "Expected $capLoadRuns cap load reports, found $($cap.Count) in $runs" }
if ($capacityRuns.Count -ne $capacityReports) { throw "Expected $capacityReports capacity reports, found $($capacityRuns.Count) in $runs" }
# A run whose every search failed (routing unreachable, a wrong token) measured nothing.
$unanswered = @(@($cap) + @($capacityRuns) | Where-Object { $_.booking.searches -eq $_.booking.failedSearches } | ForEach-Object { $_.label }) +
    @($quality | Where-Object { @($_.cases | ForEach-Object { $_.searched } | Where-Object { $_.status -eq 201 }).Count -eq 0 } | ForEach-Object { $_.label })
if ($unanswered.Count -gt 0) { throw "No search was answered in: $($unanswered -join '; ') (see the run logs in $runs)" }
[ordered]@{
    study = 'Refinement cap: offer quality and search time at threshold 2 with a longer optional refinement, and search slots against concurrent clients'
    scheduler = $Stack
    protocol = "Part cap ($(if ($doCap) { 'ran' } else { 'not run' })): per profile and fleet ($($capFleetList -join ', ') technicians), $Schedules schedules optimized with daily proposals, each with $Cases job addresses searched once per variant ($QualityVariants, threshold[:bookingRefinementMs]) in a rotated order, the best offer confirmed and priced with routes/evaluate, never written; then $DurationS s cold-address booking runs at 1 desk per variant ($($capLoadList -join ', ')). Part capacity ($(if ($doCapacity) { 'ran' } else { 'not run' })): the scheduler restarted with SCHEDULER_SEARCH_CAPACITY and CPUs of $($capacityList -join ' then '); $($clientList -join ' and ') clients at once, each its own tenant with 1 desk, $DurationS s, per variant ($($capacityVariantList -join ', ')) and fleet ($($capacityFleetList -join ', ') technicians), interleaved within a capacity. Profiles: low (1 visit a day per technician) and high (2 visits a day, the first 12 weekdays at 4). Fixed 10 weekdays throughout."
    hypotheses = @(
        'Cap, quality: at 5 technicians a complete refinement takes about 0.6 to 1.1 s, so 2:1000 recovers most of the low-volume gain threshold 1000 found ($0.37 and $0.85 a booking) and 2:500 about half; on high demand there is little to recover at 5.',
        'Cap, quality: at 10 and 15 technicians a complete refinement needs more than 1 s, so 2:1000 is SHORTENED and recovers only part of the high-demand gain threshold 1000 found at 10 (about $1.80 a booking).',
        'Cap, time: at 1 desk 2:1000 adds at most the cap and the extra roads it fetches to a search (p50 under about 1.4 s at 5 technicians), where threshold 1000 took 1.1 to 4 s.',
        'Capacity: with N clients at once and 2 slots, searches queue once N passes 2, so refinements are shed and p95 grows with N; with 5 slots on 5 CPUs, 5 clients rarely queue (shed share near 0, p50 near the 1-desk figure) and 10 queue again, less than at 2.',
        'Capacity: threshold 1000 holds a slot for 1 to 4 s a search and is never shed, so even 5 slots queue at 10 clients; 2:1000 queues less because its refinement is capped and can be shed.'
    )
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    capacitySettings = $capacitySettings
    clientTokens = $clientTokens
    runsWithDefects = $failures
    quality = $quality
    cap = $cap
    capacity = $capacityRuns
} | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
