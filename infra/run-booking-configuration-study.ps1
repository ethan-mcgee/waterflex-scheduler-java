# Booking configuration study, on the local rehearsal of the scheduler VM (deploy/vm with rehearsal.override.yml): the
# proxy, the booking pool with its replicas, slots and CPU caps, and the shipped routing and admission defaults, so
# what it measures is the layout that will run. Every search uses the dynamic horizon; the schedule profile sets how
# many dates it reaches. Parts (-Parts):
#   quality   per fleet, profile and schedule: every date optimized, then each case searched once per variant and the
#             best offer priced, so the variants compare on the same records (quality.ts).
#   load      per fleet, profile, variant and repeat: one desk booking cold addresses for -DurationS (load.ts).
#   capacity  the booking pool restarted per layout (replicas x slots, one CPU a slot); -Clients dealerships at once,
#             each its own tenant with one desk.
#   reserve   the publish reserve (SCHEDULER_BOOKING_PUBLISH_RESERVE_MS) below its default of 1000 ms, at -Reserves.
# Every run records the scheduler's own attribution from its request log (--scheduler-log), its metrics with one
# replica (--metrics-url) and docker stats. Resumable: a run whose report exists is skipped, so the matrix can be run
# over several nights with the same command; the reports live under -RunsDirectory. Long (about 60 hours with the
# defaults; -DryRun prints the matrix and its size); the owner starts it.
#   powershell -ExecutionPolicy Bypass -File infra/run-booking-configuration-study.ps1 -TokenFile .scratch/rehearsal-loadtest.token `
#     -Stack 'VM rehearsal, 8 vCPU shape, Omaha routing CH' [-Parts quality] [-DryRun]
param(
    # The rehearsal proxy. The study's tenant token; issued (tenant loadtest) through the rehearsal's scheduler-admin
    # when the file does not exist. Part capacity issues one token per client (tenants loadtest-client-<n>) on first
    # use into -ClientTokenDirectory; revoke them with scheduler-admin --command=revoke-token <id> (ids in the evidence).
    [string]$Url = 'http://127.0.0.1:18080',
    [Parameter(Mandatory)][string]$TokenFile,
    # What the scheduler under test is, recorded in the evidence (build, routing, shape).
    [Parameter(Mandatory)][string]$Stack,
    [string]$RehearsalEnv = 'deploy/vm/rehearsal.env',
    # One booking replica's actuator, published by rehearsal.override.yml; read only when the pool has one replica.
    [string]$MetricsUrl = 'http://127.0.0.1:19010/actuator/prometheus',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    # Comma-separated, as -File passes every value as text.
    [string]$Parts = 'quality,load,capacity,reserve',
    [string]$Fleets = '5,10,15,20,30',
    # empty: no visits (the dynamic horizon reaches 2 dates); light: 1 a day; half: 1 a day with 7 weekdays booked out
    # at 4 (9 dates); full: 2 a day with 15 weekdays booked out at 4 (the 15 cap).
    [string]$Profiles = 'empty,light,half,full',
    # threshold[:refinement cap ms]; the first is the baseline every other is compared with in the quality part.
    [string]$Variants = '1000,0:0,0:250,0:1000,0:2000,0:3000,2:0,2:250,2:1000,2:2000,2:3000,5:0,5:250,5:1000,5:2000,5:3000,10:0,10:250,10:1000,10:2000,10:3000',
    [ValidateRange(1, 200)][int]$Cases = 20,
    [ValidateRange(1, 5)][int]$Schedules = 2,
    [ValidateRange(1, 5)][int]$Repeats = 2,
    [ValidateRange(30, 600)][int]$DurationS = 120,
    # The booking pool's shape for every part but capacity: replicas, CPUs (threads) and slots per replica, memory.
    [ValidateRange(1, 8)][int]$Replicas = 1,
    [ValidateRange(1, 16)][int]$Cpus = 3,
    [ValidateRange(1, 16)][int]$Capacity = 3,
    [string]$Memory = '4g',
    # The daily pool's threads and the development routing service's cap for the run (the VM's partition).
    [ValidateRange(1, 8)][int]$DailyCpus = 1,
    [ValidateRange(1, 16)][int]$RoutingCpus = 2,
    # Part capacity: replicas x slots, each replica with as many CPUs as slots.
    [string]$Layouts = '1x2,2x1,1x3,3x1,1x4,2x2,4x1',
    [string]$Clients = '5,10',
    [string]$CapacityFleets = '5,10',
    [string]$CapacityProfiles = 'half,full',
    [string]$CapacityVariants = '2:250,2:1000,2:3000,1000',
    # Part reserve: the publish reserve in milliseconds (800 to 1000, the scheduler's bounds) the booking pool is restarted with.
    [string]$Reserves = '800',
    [string]$ReserveFleets = '5,10,15,30',
    [string]$ReserveProfiles = 'half,full',
    [string]$ReserveVariants = '2:3000,1000',
    [ValidateRange(800, 1000)][int]$DefaultReserve = 1000,
    # Seeds are this base plus a small cell index (the tools take 0 to 1000); the variants of a cell share a seed, so
    # they search the same addresses.
    [ValidateRange(0, 700)][int]$SeedBase = 500,
    [string]$RunsDirectory = 'experiments/runs/configuration-study',
    [string]$ClientTokenDirectory = '.scratch/configuration-clients',
    # Names the evidence file, api-load-<date>-<Study>.json.
    [ValidatePattern('^[a-z0-9-]+$')][string]$Study = 'booking-configuration',
    # Print the matrix and its size, change nothing.
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$kit = Join-Path $repository 'infra/host-conformance'
$vmCompose = Join-Path $repository 'deploy/vm/docker-compose.yml'
$rehearsalOverride = Join-Path $repository 'deploy/vm/rehearsal.override.yml'
$devCompose = Join-Path $repository 'docker-compose.yml'
$envFile = Join-Path $repository $RehearsalEnv
if (-not (Test-Path -LiteralPath $envFile)) { throw "No rehearsal env file at $envFile (see deploy/vm/README.md, Local rehearsal)" }
$runs = Join-Path $repository $RunsDirectory
New-Item -ItemType Directory -Force $runs | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$evidence = Join-Path $repository "docs/evidence/api-load-$stamp-$Study.json"

$parseList = { param($text, $name, $min, $max)
    $items = @($text -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ })
    if ($items.Count -eq 0 -or @($items | Where-Object { $_ -lt $min -or $_ -gt $max }).Count -gt 0) { throw "-$name are whole numbers from $min to $max" }
    , $items }
$parseVariants = { param($text, $name)
    $items = @($text -split ',' | ForEach-Object { $_.Trim() })
    if ($items.Count -eq 0 -or @($items | Where-Object { $_ -notmatch '^\d{1,4}(:\d{1,4})?$' }).Count -gt 0) { throw "-$name are threshold[:refinement ms]" }
    , $items }
$parseNames = { param($text, $name, $allowed)
    $items = @($text -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    if ($items.Count -eq 0 -or @($items | Where-Object { $allowed -notcontains $_ }).Count -gt 0) { throw "-$name lists $($allowed -join ', ')" }
    , $items }
# The load tool's options for a variant: 2:500 is --window-threshold 2 --refinement-ms 500.
$variantArgs = { param($variant)
    $parts = $variant -split ':'
    $arguments = @('--window-threshold', $parts[0])
    if ($parts.Count -gt 1) { $arguments += @('--refinement-ms', $parts[1]) }
    , $arguments }
$profileArgs = [ordered]@{
    empty = @('--appointments', '0', '--booked-weekdays', '0')
    light = @('--appointments', '1', '--booked-weekdays', '0')
    half  = @('--appointments', '1', '--booked-weekdays', '7', '--booked-appointments', '4')
    full  = @('--appointments', '2', '--booked-weekdays', '15', '--booked-appointments', '4')
}
$partList = & $parseNames $Parts 'Parts' @('quality', 'load', 'capacity', 'reserve')
$fleetList = & $parseList $Fleets 'Fleets' 1 200
$profileList = & $parseNames $Profiles 'Profiles' @($profileArgs.Keys)
$variantList = & $parseVariants $Variants 'Variants'
$layoutList = @($Layouts -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } | ForEach-Object {
    if ($_ -notmatch '^(\d{1,2})x(\d{1,2})$') { throw '-Layouts are replicas x slots, such as 1x3' }
    @{ name = $_; replicas = [int]$Matches[1]; slots = [int]$Matches[2] } })
if ($layoutList.Count -eq 0) { throw '-Layouts is empty' }
$clientList = & $parseList $Clients 'Clients' 1 20
$capacityFleetList = & $parseList $CapacityFleets 'CapacityFleets' 1 200
$capacityProfileList = & $parseNames $CapacityProfiles 'CapacityProfiles' @($profileArgs.Keys)
$capacityVariantList = & $parseVariants $CapacityVariants 'CapacityVariants'
$reserveList = & $parseList $Reserves 'Reserves' 800 1000
$reserveFleetList = & $parseList $ReserveFleets 'ReserveFleets' 1 200
$reserveProfileList = & $parseNames $ReserveProfiles 'ReserveProfiles' @($profileArgs.Keys)
$reserveVariantList = & $parseVariants $ReserveVariants 'ReserveVariants'
# Quality runs judge every variant against the uncapped threshold-1000 search on the same case, so it must be among them.
foreach ($pair in @(@('Variants', $variantList), @('ReserveVariants', $reserveVariantList))) {
    if (@($pair[1] | Where-Object { $_ -eq '1000' }).Count -eq 0) { throw "-$($pair[0]) must include 1000, the quality reference" } }

# The matrix, as cells; each cell names its runs, and a run is skipped when its report already exists.
$cells = @()
if ($partList -contains 'quality') {
    foreach ($fleet in $fleetList) { foreach ($profile in $profileList) { for ($schedule = 1; $schedule -le $Schedules; $schedule++) {
        $cells += @{ part = 'quality'; fleet = $fleet; profile = $profile; schedule = $schedule; tool = 'quality'; variants = $Variants; clients = 1; reserve = $DefaultReserve; layout = $null }
    } } }
}
if ($partList -contains 'load') {
    foreach ($fleet in $fleetList) { foreach ($profile in $profileList) { foreach ($variant in $variantList) { for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        $cells += @{ part = 'load'; fleet = $fleet; profile = $profile; variant = $variant; repeat = $repeat; tool = 'load'; clients = 1; reserve = $DefaultReserve; layout = $null }
    } } } }
}
if ($partList -contains 'capacity') {
    foreach ($layout in $layoutList) { foreach ($count in $clientList) { foreach ($fleet in $capacityFleetList) { foreach ($profile in $capacityProfileList) { foreach ($variant in $capacityVariantList) {
        $cells += @{ part = 'capacity'; fleet = $fleet; profile = $profile; variant = $variant; repeat = 1; tool = 'load'; clients = $count; reserve = $DefaultReserve; layout = $layout }
    } } } } }
}
if ($partList -contains 'reserve') {
    foreach ($reserve in $reserveList) { foreach ($fleet in $reserveFleetList) { foreach ($profile in $reserveProfileList) {
        $cells += @{ part = 'reserve'; fleet = $fleet; profile = $profile; schedule = 1; tool = 'quality'; variants = $ReserveVariants; clients = 1; reserve = $reserve; layout = $null }
        foreach ($variant in $reserveVariantList) { for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
            $cells += @{ part = 'reserve'; fleet = $fleet; profile = $profile; variant = $variant; repeat = $repeat; tool = 'load'; clients = 1; reserve = $reserve; layout = $null }
        } }
    } } }
}
# A run's directory names its cell, so a second start finds the report of a finished run and skips it.
$slugOf = { param($cell)
    $parts = @("$($cell.fleet)t", $cell.profile)
    if ($cell.tool -eq 'quality') { $parts += "schedule$($cell.schedule)" } else { $parts += @("v$($cell.variant -replace ':', 'r')", "repeat$($cell.repeat)") }
    if ($null -ne $cell.layout) { $parts += @("layout$($cell.layout.name)", "clients$($cell.clients)") }
    if ($cell.reserve -ne $DefaultReserve) { $parts += "reserve$($cell.reserve)" }
    $parts -join '-' }
# The variants of a cell share a seed, and so the same addresses; clients of one cell have their own.
$seedOf = { param($cell, $client)
    $fleetIndex = [array]::IndexOf($fleetList, $cell.fleet); if ($fleetIndex -lt 0) { $fleetIndex = [array]::IndexOf($capacityFleetList, $cell.fleet) + $fleetList.Count }
    $profileIndex = [array]::IndexOf(@($profileArgs.Keys), $cell.profile)
    $repeat = if ($cell.tool -eq 'quality') { $cell.schedule } else { $cell.repeat }
    $partIndex = [array]::IndexOf(@('quality', 'load', 'capacity', 'reserve'), $cell.part)
    $SeedBase + $partIndex * 50 + $fleetIndex * 8 + $profileIndex * 2 + ($repeat - 1) + ($client - 1) * 20 }
$reportsOf = { param($directory)
    if (-not (Test-Path -LiteralPath $directory)) { return , @() }
    , @(Get-ChildItem -LiteralPath $directory -Filter '*.json' -Recurse | Sort-Object FullName) }
$done = @($cells | Where-Object { $cell = $_; $expected = $cell.clients
    (& $reportsOf (Join-Path $runs "$($cell.part)/$(& $slugOf $cell)")).Count -ge $expected }).Count
$minutes = 0
foreach ($cell in $cells) {
    $minutes += if ($cell.tool -eq 'quality') { 2 + $Cases * (($cell.variants -split ',').Count) * 1.8 / 60 } else { $DurationS / 60 + 0.5 }
}
Write-Output "Matrix: $($cells.Count) cells ($done finished already) in parts $($partList -join ', '); about $([math]::Round($minutes / 60, 1)) hours in all"
foreach ($part in $partList) { Write-Output "  $part`: $(@($cells | Where-Object { $_.part -eq $part }).Count) cells" }
if ($DryRun) { foreach ($cell in $cells) { Write-Output "  $($cell.part)/$(& $slugOf $cell) seed $(& $seedOf $cell 1)" }; return }
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }
foreach ($cell in $cells) { if ((& $seedOf $cell $cell.clients) -gt 1000) { throw 'A seed would pass 1000; lower -SeedBase' } }

# Compose: the rehearsal stack with the study's shape on top, and the development stack for its routing service.
$rehearsal = @('-f', $vmCompose, '-f', $rehearsalOverride, '--env-file', $envFile)
$docker = { param($arguments, $log)
    # Docker writes progress to stderr, which must not stop the script.
    $ErrorActionPreference = 'Continue'
    try { & docker @arguments *> $log; $code = $LASTEXITCODE } finally { $ErrorActionPreference = 'Stop' }
    if ($code -ne 0) { throw "docker $($arguments -join ' ') failed (see $log)" } }
$dockerOutput = { param($arguments)
    $ErrorActionPreference = 'Continue'
    try { $output = & docker @arguments 2>&1 | Out-String; $code = $LASTEXITCODE } finally { $ErrorActionPreference = 'Stop' }
    if ($code -ne 0) { throw "docker $($arguments -join ' ') failed`n$output" }
    $output }
# Container names, not IDs: docker stats reports names, and the kit matches what it was given against them.
$bookingContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-booking'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$dailyContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-daily'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$allHealthy = { param($ids)
    foreach ($id in $ids) { if ((& $dockerOutput @('inspect', '--format', '{{.State.Health.Status}}', $id)).Trim() -ne 'healthy') { return $false } }
    $true }
# The status the proxy answers for a path, or 0 when it answers nothing; a 4xx proves the pool behind the path is up.
$proxyStatus = { param($path)
    try { return [int](Invoke-WebRequest "$Url$path" -UseBasicParsing -TimeoutSec 5).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return 0 } } }
# Both pools healthy and reached through the proxy: the booking pool on /health, the daily pool on a request status
# route (the proxy re-resolves a pool's addresses only every 10 s, so a healthy replica can still be unreachable).
# A replica that has restarted since it was created is crash-looping on its settings: the gate fails at once with its log.
$crashed = { param($ids)
    foreach ($id in $ids) {
        if ([int](& $dockerOutput @('inspect', '--format', '{{.RestartCount}}', $id)).Trim() -gt 0) {
            $tail = & $dockerOutput @('logs', '--tail', '200', $id)
            $cause = ($tail -split "`r?`n" | Where-Object { $_ -match 'Exception: ' } | Select-Object -Last 1)
            throw "Container $id restarted on its settings: $cause" } } }
$health = { param($seconds, $replicas)
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        # A docker call that fails while the pool is being recreated is retried; a crash-looping replica is not.
        try { $ids = & $bookingContainers; $daily = & $dailyContainers } catch { Start-Sleep -Seconds 3; continue }
        & $crashed ($ids + $daily)
        try {
            $dailyStatus = & $proxyStatus '/api/v1/requests/study-health-probe'
            if ($ids.Count -eq $replicas -and (& $allHealthy $ids) -and $daily.Count -ge 1 -and (& $allHealthy $daily) -and
                (& $proxyStatus '/health') -eq 200 -and $dailyStatus -ge 200 -and $dailyStatus -lt 500) { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    $false }

$current = $null
$shapes = @()
# Restarts the booking pool with a layout and reserve, verified on every replica; the daily pool at its study size.
$setShape = { param($replicas, $cpus, $slots, $reserve)
    $wanted = "$($replicas)x$($slots)c$($cpus)r$reserve"
    if ($script:current -eq $wanted) { return }
    $override = Join-Path $runs "shape-$wanted.yml"
    $pool = $slots + 6
    @(
        'services:', '  scheduler-booking:',
        # The rehearsal publishes one replica's management port (the metrics a one-replica load run reads); two replicas
        # cannot share it, so the port is dropped only then.
        $(if ($replicas -gt 1) { '    ports: !reset []' }),
        '    environment:', "      SCHEDULER_SEARCH_CAPACITY: `"$slots`"", "      SCHEDULER_DB_POOL_SIZE: `"$pool`"", "      SCHEDULER_BOOKING_PUBLISH_RESERVE_MS: `"$reserve`"",
        '    deploy:', "      replicas: $replicas", '      resources:', "        limits: { cpus: `"$cpus`", memory: `"$Memory`" }",
        '  scheduler-daily:',
        # The container reads SCHEDULER_SEARCH_BACKGROUND_LIMIT (the VM compose maps SCHEDULER_DAILY_BACKGROUND_LIMIT to it);
        # admission refuses a background limit above the capacity, so both are set to 1 here.
        '    environment:', '      SCHEDULER_SEARCH_CAPACITY: "1"', '      SCHEDULER_SEARCH_BACKGROUND_LIMIT: "1"', '      SCHEDULER_DAILY_QUEUE_WORKERS: "1"',
        '    deploy:', '      resources:', "        limits: { cpus: `"$DailyCpus`", memory: `"2g`" }"
    ) | Set-Content -LiteralPath $override -Encoding ascii
    Write-Output "Shape: booking pool $replicas x $slots slots on $cpus CPUs each, pool $pool, reserve $reserve ms; daily pool 1 slot on $DailyCpus CPUs"
    & $docker (@('compose') + $rehearsal + @('-f', $override, 'up', '-d', '--no-build', '--remove-orphans', 'scheduler-booking', 'scheduler-daily')) (Join-Path $runs "restart-$wanted.log")
    $script:current = $wanted
    if (-not (& $health 300 $replicas)) { throw "The booking pool did not come back as $wanted (see restart-$wanted.log)" }
    Start-Sleep -Seconds 5
    $ids = & $bookingContainers
    $verified = @()
    foreach ($id in $ids) {
        $inspect = (& $dockerOutput @('inspect', $id)) | ConvertFrom-Json
        $configured = @($inspect[0].Config.Env | Where-Object { $_ -like 'SCHEDULER_SEARCH_CAPACITY=*' -or $_ -like 'SCHEDULER_DB_POOL_SIZE=*' -or $_ -like 'SCHEDULER_BOOKING_PUBLISH_RESERVE_MS=*' })
        if ($configured -notcontains "SCHEDULER_SEARCH_CAPACITY=$slots" -or $configured -notcontains "SCHEDULER_DB_POOL_SIZE=$pool" -or
            $configured -notcontains "SCHEDULER_BOOKING_PUBLISH_RESERVE_MS=$reserve" -or $inspect[0].HostConfig.NanoCpus -ne [long]$cpus * 1000000000) {
            throw "Replica $id does not have $slots slots, pool $pool, reserve $reserve on $cpus CPUs"
        }
        $verified += [ordered]@{ container = $id; nanoCpus = $inspect[0].HostConfig.NanoCpus; memory = $inspect[0].HostConfig.Memory; environment = $configured }
    }
    $script:shapes += [ordered]@{ shape = $wanted; replicas = $replicas; cpus = $cpus; slots = $slots; pool = $pool; reserve = $reserve; replicasVerified = $verified }
}

# The development stack's routing service stands in for the VM's; cap it as the VM does for the run.
$routingOverride = Join-Path $runs 'routing-cap.yml'
@('services:', '  routing-service:', "    cpus: $RoutingCpus") | Set-Content -LiteralPath $routingOverride -Encoding ascii
$routingCapped = $false
$routingName = (& $dockerOutput @('compose', '-f', $devCompose, 'ps', '--format', '{{.Name}}', 'routing-service')).Trim()
if ($routingName -eq '') { throw 'The development routing service is not running (docker compose up routing-service)' }

# Tokens: the study's own, and one per client, through the rehearsal's scheduler-admin.
$issue = { param($tenant, $title, $file)
    $output = & $dockerOutput (@('compose') + $rehearsal + @('run', '--rm', 'scheduler-admin', '--command=issue-token', $tenant, $title, 'booking configuration study'))
    $id = [regex]::Match($output, '(?m)^id: (\S+)').Groups[1].Value
    $value = [regex]::Match($output, '(?m)^token: (wfs_\S+)').Groups[1].Value
    if ($id -eq '' -or $value -eq '') { throw "Could not issue a token for $tenant`n$output" }
    New-Item -ItemType Directory -Force (Split-Path $file -Parent) | Out-Null
    Set-Content -LiteralPath $file -Value $value -Encoding ascii -NoNewline
    Set-Content -LiteralPath "$file.id" -Value $id -Encoding ascii -NoNewline }
$clientTokens = @()
if (-not (Test-Path -LiteralPath $TokenFile)) { & $issue 'loadtest' 'Load test' $TokenFile; Write-Output "Issued the study's token into $TokenFile" }
$token = (Resolve-Path -LiteralPath $TokenFile).Path
$clientToken = { param($n)
    $file = Join-Path (Join-Path $repository $ClientTokenDirectory) "client-$n.token"
    if (-not (Test-Path -LiteralPath $file)) { & $issue "loadtest-client-$n" "Load test client $n" $file }
    $script:clientTokens += [ordered]@{ client = $n; tenant = "loadtest-client-$n"; tokenId = (Get-Content -LiteralPath "$file.id" -Raw).Trim() }
    $file }

$common = @('--url', $Url, '--metro', $Metro, '--time-zone', $TimeZone, '--area', $Area, '--horizon', 'dynamic', '--horizon-weekdays', '15', '--lead-weekdays', '2', '--full-percent', '85')
# Start-Process in Windows PowerShell joins its arguments with spaces and quotes none of them.
$quoted = { param($arguments) , @($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) }
# What a run reads beside its own measurements: every booking replica's request log; for a load run also docker stats
# of the replicas and the routing service, and with one replica its metrics (quality.ts has neither option).
$observed = { param($replicas, $tool)
    $ids = & $bookingContainers
    $arguments = @('--scheduler-log', ($ids -join ','))
    if ($tool -eq 'load') {
        $arguments += @('--docker', (@($ids) + @($routingName) -join ','))
        if ($replicas -eq 1 -and $MetricsUrl -ne '') { $arguments += @('--metrics-url', $MetricsUrl) }
    }
    , $arguments }

$index = 0
$skipped = 0
Push-Location $kit
try {
    & $docker @('compose', '-f', $devCompose, '-f', $routingOverride, 'up', '-d', '--no-deps', '--no-build', 'routing-service') (Join-Path $runs 'routing-cap.log')
    $routingCapped = $true
    foreach ($cell in $cells) {
        $index++
        $slug = & $slugOf $cell
        $directory = Join-Path $runs "$($cell.part)/$slug"
        if ((& $reportsOf $directory).Count -ge $cell.clients) { $skipped++; continue }
        $layout = if ($null -eq $cell.layout) { @{ replicas = $Replicas; slots = $Capacity; cpus = $Cpus } } else { @{ replicas = $cell.layout.replicas; slots = $cell.layout.slots; cpus = $cell.layout.slots } }
        & $setShape $layout.replicas $layout.cpus $layout.slots $cell.reserve
        New-Item -ItemType Directory -Force $directory | Out-Null
        # A cell is run whole: reports left by an attempt that did not finish every client would be counted twice.
        Get-ChildItem -LiteralPath $directory -Recurse -File | Where-Object { $_.Extension -in '.json', '.md' } | Remove-Item -Force
        $label = "$($cell.part) $slug"
        Write-Output "[$index/$($cells.Count)] $label"
        if ($cell.tool -eq 'quality') {
            $seed = & $seedOf $cell 1
            $arguments = @('tsx', 'quality.ts') + $common + @('--token-file', $token, '--variants', $cell.variants, '--cases', "$Cases", '--seed', "$seed",
                '--label', "$label seed $seed", '--out', $directory, '--technicians', "$($cell.fleet)") + $profileArgs[$cell.profile] + (& $observed $layout.replicas 'quality')
            & npx.cmd @arguments *> (Join-Path $directory 'run.log')
            # Exit 1 is a run with breaches, which the report records; a crash also exits 1, so the report is required.
            if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) { throw "Run failed: $label (see $directory/run.log)" }
            if ((& $reportsOf $directory).Count -lt 1) { throw "Run wrote no report: $label (see $directory/run.log)" }
            continue
        }
        $processes = @()
        for ($client = 1; $client -le $cell.clients; $client++) {
            $seed = & $seedOf $cell $client
            $clientFile = if ($cell.clients -eq 1) { $token } else { & $clientToken $client }
            $out = if ($cell.clients -eq 1) { $directory } else { Join-Path $directory "client-$client" }
            $arguments = @('tsx', 'load.ts') + $common + @('--token-file', $clientFile, '--scenario', 'booking', '--concurrency', '1', '--duration-s', "$DurationS",
                '--seed', "$seed", '--label', "$label client $client of $($cell.clients) seed $seed", '--out', $out, '--technicians', "$($cell.fleet)") + (& $variantArgs $cell.variant) + $profileArgs[$cell.profile]
            # The request log and metrics are the pool's, so one client of a cell reads them.
            if ($client -eq 1) { $arguments += (& $observed $layout.replicas 'load') }
            New-Item -ItemType Directory -Force $out | Out-Null
            $process = Start-Process -FilePath 'npx.cmd' -ArgumentList (& $quoted $arguments) -NoNewWindow -PassThru `
                -RedirectStandardOutput (Join-Path $out 'run.log') -RedirectStandardError (Join-Path $out 'run.err.log')
            # Windows PowerShell reports a started process's exit code only if its handle was read while it ran.
            $null = $process.Handle
            $processes += @{ process = $process; client = $client }
        }
        foreach ($entry in $processes) {
            $entry.process.WaitForExit()
            $code = $entry.process.ExitCode
            if ($code -ne 0 -and $code -ne 1) { throw "Run failed: $label client $($entry.client) (see $directory)" }
        }
        if ((& $reportsOf $directory).Count -lt $cell.clients) { throw "Run wrote no report: $label (see $directory, run.err.log of each client)" }
    }
} finally {
    Pop-Location
    $ErrorActionPreference = 'Continue'
    if ($null -ne $current) {
        # Back to the rehearsal's own settings: one replica of each pool as rehearsal.env says, the management port published.
        & docker compose @rehearsal up -d --no-build --remove-orphans scheduler-booking scheduler-daily *> (Join-Path $runs 'restore.log')
        if (-not (& $health 300 1)) { Write-Warning "The booking pool did not come back after restoring its settings (see $runs/restore.log)" }
    }
    if ($routingCapped) { & docker compose -f $devCompose up -d --no-deps --no-build routing-service *> (Join-Path $runs 'routing-restore.log') }
    $ErrorActionPreference = 'Stop'
}
Write-Output "Ran $($cells.Count - $skipped) cells, skipped $skipped already finished"

# The evidence: every report of every cell, with what each cell was.
$read = { param($cell)
    $directory = Join-Path $runs "$($cell.part)/$(& $slugOf $cell)"
    , @(& $reportsOf $directory | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json }) }
$entries = @()
$missing = @()
$defects = @()
$unanswered = @()
foreach ($cell in $cells) {
    $reports = & $read $cell
    if ($reports.Count -lt $cell.clients) { $missing += "$($cell.part)/$(& $slugOf $cell)"; continue }
    foreach ($report in $reports) {
        # A quality report has no hostAnomalies field; @($null).Count is 1 in PowerShell, so nulls are dropped first.
        $breaches = @($report.contractBreaches | Where-Object { $null -ne $_ }).Count + @($report.hostAnomalies | Where-Object { $null -ne $_ }).Count
        if ($breaches -gt 0) { $defects += $report.label }
        $answered = if ($cell.tool -eq 'quality') { @($report.cases | ForEach-Object { $_.searched } | Where-Object { $_.status -eq 201 }).Count }
                    else { $report.booking.searches - $report.booking.failedSearches }
        if ($answered -eq 0) {
            # A publish reserve below the database admission guard (connection plus validation timeout) refuses the publish
            # of every search that explores to the end of its time, as a required refinement does: that is the reserve
            # part's finding, recorded. Anywhere else a run without one answer is a broken stack.
            if ($cell.part -ne 'reserve') { throw "No search was answered in $($report.label) (see $runs)" }
            $unanswered += [ordered]@{ label = $report.label; statuses = $report.operations[0].statuses; errors = $report.operations[0].errors }
        }
    }
    $layout = if ($null -eq $cell.layout) { [ordered]@{ replicas = $Replicas; slots = $Capacity; cpus = $Cpus } } else { [ordered]@{ replicas = $cell.layout.replicas; slots = $cell.layout.slots; cpus = $cell.layout.slots } }
    $entries += [ordered]@{ part = $cell.part; fleet = $cell.fleet; profile = $cell.profile; variant = $(if ($cell.tool -eq 'quality') { $null } else { $cell.variant })
        schedule = $(if ($cell.tool -eq 'quality') { $cell.schedule } else { $null }); repeat = $(if ($cell.tool -eq 'quality') { $null } else { $cell.repeat })
        clients = $cell.clients; layout = $layout; reserveMs = $cell.reserve; reports = $reports }
}
if ($missing.Count -gt 0) { throw "Cells without their reports (run the same command again to finish them): $($missing -join '; ')" }
# Read from the token files rather than this invocation's runs, so a resumed run that skipped every cell still lists them, once each.
$clientTokens = @(Get-ChildItem -LiteralPath (Join-Path $repository $ClientTokenDirectory) -Filter 'client-*.token.id' -ErrorAction SilentlyContinue |
    ForEach-Object { $n = [int]([regex]::Match($_.Name, '^client-(\d+)\.token\.id$').Groups[1].Value)
        [ordered]@{ client = $n; tenant = "loadtest-client-$n"; tokenId = (Get-Content -LiteralPath $_.FullName -Raw).Trim() } } | Sort-Object { $_.client })
# Every shape the study set, from the overrides it wrote; shapesVerified holds the replicas inspected by this invocation only.
$shapesConfigured = @(Get-ChildItem -LiteralPath $runs -Filter 'shape-*.yml' | ForEach-Object { $_.BaseName -replace '^shape-', '' } | Sort-Object)
[ordered]@{
    study = 'Booking configuration: window threshold crossed with the optional refinement cap (to 3000 ms), on empty to full schedules under the dynamic horizon, at 5 to 30 technicians, with the booking pool layouts and the publish reserve, on the VM rehearsal'
    scheduler = $Stack
    stack = 'deploy/vm rehearsal (proxy, booking and daily pools, VM compute caps; the development routing service capped to the VM partition), shipped routing and admission defaults'
    shape = [ordered]@{ bookingReplicas = $Replicas; bookingCpus = $Cpus; bookingSlots = $Capacity; bookingMemory = $Memory; bookingPool = $Capacity + 6; dailyCpus = $DailyCpus; routingCpus = $RoutingCpus; cpusAreThreads = 'Docker cpus count hardware threads: on the Ryzen 9 7900X (12 cores, 24 threads) and on a VM vCPU alike' }
    protocol = "Dynamic horizon (cap 15 weekdays, lead 2, full at 85 percent) on every search. Profiles: empty (no visits), light (1 a day), half (1 a day, 7 weekdays booked out at 4 visits), full (2 a day, 15 weekdays booked out at 4). Variants threshold[:bookingRefinementMs]: $Variants (the first the quality baseline). Quality: per fleet ($($fleetList -join ', ') technicians), profile and schedule ($Schedules), every date optimized with daily proposals, $Cases job addresses searched once per variant in a rotated order, the best offer confirmed and priced, never written. Load: 1 desk, cold addresses, $DurationS s, $Repeats repeats per fleet, profile and variant. Capacity: booking pool layouts $Layouts (replicas x slots, a CPU a slot), $($clientList -join ' and ') clients at once each its own tenant with 1 desk, fleets $CapacityFleets, profiles $CapacityProfiles, variants $CapacityVariants. Reserve: publish reserve $Reserves ms at fleets $ReserveFleets, profiles $ReserveProfiles, variants $ReserveVariants, one quality schedule and $Repeats load repeats each. Every run reads the scheduler's request log (serverBreakdown) and docker stats; one-replica runs read the replica's metrics."
    hypotheses = @(
        'At 5 technicians 2:1000 matches 1000 on cost; at 10 and 15 a cap of 2000 or 3000 is needed; at 20 and 30 every cap above 2000 stops on DEADLINE and none matches 1000.',
        'x:0 is $1 to $3 a booking dearer than 1000 on half and full schedules at 10 to 20 technicians and within $0.50 on empty and light: the refinement buys the saving, the threshold only decides when it is required.',
        'regularWindowsFound on empty and light schedules (2 dates) is small enough that thresholds 5 and 10 make the refinement required on a share of searches; on half and full it is never at or below 10, so those thresholds are inert there.',
        'calculationMs grows with the cap and the fleet; routingMs with the fleet and the dates; queueMs is near zero at 1 desk and is what extra slots remove.',
        'At 1 desk throughput falls in proportion to the cap once the refinement is longer than the first pass. At 5 clients on 2 slots caps of 1000 and above are shed on 80 percent or more of searches; on 4 cores under 30 percent; aggregate searches per minute on 4 cores is at least 1.8 times the 2-core figure. 10 clients shed on every layout inside 4 cores with caps above 250.',
        'At equal cores one replica with more slots beats several replicas with fewer on p50 and routing time (shared caches, no blind round-robin), by 10 to 20 percent.',
        'The commit phase takes under 150 ms p99 at 5 to 15 technicians and 400 to 900 ms at 30; a 500 ms reserve is safe to 15 technicians and gives 2:3000 the cost of 1000 there; a 250 ms reserve fails publishes at 20 and above.'
    )
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    shapesConfigured = $shapesConfigured
    shapesVerified = $shapes
    clientTokens = $clientTokens
    runsWithDefects = $defects
    unansweredRuns = $unanswered
    cells = $entries
} | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
