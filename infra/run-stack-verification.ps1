# Stack verification of a promoted change: the same daily proposals on the local rehearsal of the scheduler VM
# (deploy/vm with rehearsal.override.yml), once on an image built from -Before and once on an image built from -After,
# through the public API with the shipped routing and admission defaults. Paired solver studies run on the workstation
# harness; this run checks that what they promoted holds on the stack (AGENTS.md, Benchmarks and studies).
#   step 1  one planner, synchronous, at each fleet in -Fleets and each points seed in -Seeds, -Proposals proposals.
#   step 2  -AsyncPlanners planners queuing (Prefer: respond-async) on the -AsyncFleet fleet, -AsyncProposals in all:
#           the daily pool's real shape, a background limit of 2 on 2 CPUs.
# Each image is built in its own git worktree under its own tag and run with SCHEDULER_PULL_POLICY=never, so the stack
# cannot rebuild the working tree under the run. The daily pool runs with SCHEDULER_STUDY_RECEIPTS=true and
# TIMEFOLD_LOG_LEVEL=INFO, so every run also reads each calculation's study receipt and Timefold's move evaluation speed
# per phase from the replica's log (--scheduler-log). The images run in the order before, after for step 1 and after,
# before for step 2, so neither always runs first. Resumable: a run whose report exists is skipped. About 45 minutes
# with the defaults (-DryRun prints the runs); the owner starts it.
#   powershell -ExecutionPolicy Bypass -File infra/run-stack-verification.ps1 -Before <commit> -After <commit> [-DryRun]
param(
    # The commits whose images are compared; any revision git resolves.
    [Parameter(Mandatory)][string]$Before,
    [Parameter(Mandatory)][string]$After,
    [string]$Url = 'http://127.0.0.1:18080',
    # The study's tenant token; issued (tenant loadtest) through the rehearsal's scheduler-admin when the file does not exist.
    [string]$TokenFile = '.scratch/rehearsal-loadtest.token',
    [string]$RehearsalEnv = 'deploy/vm/rehearsal.env',
    # The daily replica's actuator, published by rehearsal.override.yml.
    [string]$MetricsUrl = 'http://127.0.0.1:19011/actuator/prometheus',
    [string]$Metro = 'metro-omaha',
    [string]$TimeZone = 'America/Chicago',
    [string]$Area = '41.22,-96.05,41.28,-95.95',
    # Comma-separated, as -File passes every value as text.
    [string]$Fleets = '10,20,50',
    [string]$Seeds = '1,2,3',
    [ValidateRange(1, 50)][int]$Proposals = 4,
    [ValidateRange(1, 200)][int]$AsyncFleet = 20,
    [ValidateRange(1, 8)][int]$AsyncPlanners = 2,
    [ValidateRange(1, 100)][int]$AsyncProposals = 12,
    [ValidateRange(0, 1000)][int]$AsyncSeed = 4,
    # The production allocation: 10 s cost search, 5 s fairness search. A fairness time of 0 would never run option b.
    [ValidateRange(5, 120)][int]$CostSeconds = 10,
    [ValidateRange(1, 120)][int]$FairnessSeconds = 5,
    [ValidateRange(1, 4)][int]$Appointments = 3,
    # The development routing service's cap for the run (the VM's partition).
    [ValidateRange(1, 16)][int]$RoutingCpus = 2,
    [string]$RunsDirectory = '.scratch/stack-verification',
    # Names the evidence file, api-load-<date>-<Study>.json.
    [ValidatePattern('^[a-z0-9-]+$')][string]$Study = 'binary-variance',
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
$fleetList = & $parseList $Fleets 'Fleets' 1 200
$seedList = & $parseList $Seeds 'Seeds' 0 1000
$resolve = { param($revision)
    $sha = (git -C $repository rev-parse --verify "$revision^{commit}" 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $sha) { throw "Not a commit: $revision" }
    $sha.Trim() }
$images = [ordered]@{}
foreach ($pair in @(@('before', $Before), @('after', $After))) {
    $sha = & $resolve $pair[1]
    $images[$pair[0]] = [ordered]@{ name = $pair[0]; revision = $pair[1]; commit = $sha; tag = "waterflex-scheduler-engine:study-$($pair[0])-$($sha.Substring(0, 12))" }
}
if ($images.before.commit -eq $images.after.commit) { throw '-Before and -After are the same commit' }

# The runs, in order: step 1 before then after, step 2 after then before.
$cells = @()
foreach ($image in @('before', 'after')) { foreach ($fleet in $fleetList) { foreach ($seed in $seedList) {
    $cells += [ordered]@{ step = 1; image = $image; fleet = $fleet; seed = $seed; planners = 1; mode = 'sync'; proposals = $Proposals } } } }
foreach ($image in @('after', 'before')) {
    $cells += [ordered]@{ step = 2; image = $image; fleet = $AsyncFleet; seed = $AsyncSeed; planners = $AsyncPlanners; mode = 'async'; proposals = $AsyncProposals } }
$slugOf = { param($cell) "step$($cell.step)-$($cell.image)-$($cell.fleet)t-seed$($cell.seed)-$($cell.mode)$($cell.planners)" }
$reportOf = { param($cell)
    $directory = Join-Path $runs (& $slugOf $cell)
    if (-not (Test-Path -LiteralPath $directory)) { return $null }
    Get-ChildItem -LiteralPath $directory -Filter 'load-daily-*.json' | Select-Object -First 1 }
# A proposal's searches plus routing and the call; a queued pair runs two at once.
$minutes = 8
foreach ($cell in $cells) { $minutes += $cell.proposals / $cell.planners * ($CostSeconds + $FairnessSeconds + 3) / 60 + 0.3 }
$done = @($cells | Where-Object { $null -ne (& $reportOf $_) }).Count
Write-Output "Images: before $($images.before.commit) ($($images.before.tag)), after $($images.after.commit) ($($images.after.tag))"
Write-Output "Runs: $($cells.Count) ($done finished already); about $([math]::Round($minutes)) minutes with the image builds"
if ($DryRun) { foreach ($cell in $cells) { Write-Output "  $(& $slugOf $cell): $($cell.proposals) proposals" }; return }
if (Test-Path -LiteralPath $evidence) { throw "Evidence already exists: $evidence" }

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

# The images: each commit in its own worktree, built once under its own tag.
foreach ($image in $images.Values) {
    $existing = (& $dockerOutput @('images', '-q', $image.tag)).Trim()
    if ($existing -ne '') { Write-Output "Image $($image.tag) exists"; continue }
    $tree = Join-Path $runs "tree-$($image.name)"
    if (Test-Path -LiteralPath $tree) { git -C $repository worktree remove --force $tree | Out-Null }
    # The image builds in Linux: the tree keeps the repository's LF endings (mvnw), whatever core.autocrlf says here.
    git -C $repository -c core.autocrlf=false worktree add --detach $tree $image.commit | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not check out $($image.commit) into $tree" }
    try {
        Write-Output "Building $($image.tag) from $($image.commit)"
        & $docker @('build', '-t', $image.tag, '-f', (Join-Path $tree 'scheduler-service/Dockerfile'), $tree) (Join-Path $runs "build-$($image.name).log")
    } finally { git -C $repository worktree remove --force $tree | Out-Null }
}
foreach ($name in @($images.Keys)) { $images[$name]['imageId'] = (& $dockerOutput @('images', '--no-trunc', '-q', $images[$name].tag)).Trim() }

$rehearsal = @('-f', $vmCompose, '-f', $rehearsalOverride, '--env-file', $envFile)
$dailyContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-daily'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$bookingContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-booking'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$proxyStatus = { param($path)
    try { return [int](Invoke-WebRequest "$Url$path" -UseBasicParsing -TimeoutSec 5).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return 0 } } }
$healthy = { param($ids)
    foreach ($id in $ids) { if ((& $dockerOutput @('inspect', '--format', '{{.State.Health.Status}}', $id)).Trim() -ne 'healthy') { return $false } }
    $ids.Count -gt 0 }
# Both pools healthy and reached through the proxy: the booking pool on /health, the daily pool on a request status route.
$health = { param($seconds)
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        try {
            $daily = & $dailyContainers; $booking = & $bookingContainers
            $dailyStatus = & $proxyStatus '/api/v1/requests/study-health-probe'
            if ((& $healthy $daily) -and (& $healthy $booking) -and (& $proxyStatus '/health') -eq 200 -and $dailyStatus -ge 200 -and $dailyStatus -lt 500) { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    $false }

# The stack on one image, verified on the daily replica: its image ID and the study's settings.
$running = $null
$verified = @()
$useImage = { param($name)
    if ($script:running -eq $name) { return }
    $image = $images[$name]
    $env:SCHEDULER_IMAGE = $image.tag; $env:SCHEDULER_PULL_POLICY = 'never'
    $env:SCHEDULER_STUDY_RECEIPTS = 'true'; $env:TIMEFOLD_LOG_LEVEL = 'INFO'
    Write-Output "Stack on $name ($($image.tag))"
    & $docker (@('compose') + $rehearsal + @('up', '-d', '--no-build', '--remove-orphans')) (Join-Path $runs "up-$name.log")
    if (-not (& $health 300)) { throw "The stack did not come up on $($image.tag) (see up-$name.log)" }
    foreach ($id in (& $dailyContainers) + (& $bookingContainers)) {
        $inspect = (& $dockerOutput @('inspect', $id)) | ConvertFrom-Json
        $settings = @($inspect[0].Config.Env | Where-Object { $_ -like 'SCHEDULER_STUDY_RECEIPTS=*' -or $_ -like 'TIMEFOLD_LOG_LEVEL=*' })
        if ($inspect[0].Image -ne $image.imageId -or $settings -notcontains 'SCHEDULER_STUDY_RECEIPTS=true' -or $settings -notcontains 'TIMEFOLD_LOG_LEVEL=INFO') {
            throw "Container $id does not run $($image.tag) with study receipts and Timefold at INFO" }
        $script:verified += [ordered]@{ image = $name; container = $id; imageId = $inspect[0].Image; nanoCpus = $inspect[0].HostConfig.NanoCpus; memory = $inspect[0].HostConfig.Memory; environment = $settings }
    }
    $script:running = $name
}

# The development stack's routing service stands in for the VM's; cap it as the VM does for the run.
$routingOverride = Join-Path $runs 'routing-cap.yml'
@('services:', '  routing-service:', "    cpus: $RoutingCpus") | Set-Content -LiteralPath $routingOverride -Encoding ascii
$routingName = (& $dockerOutput @('compose', '-f', $devCompose, 'ps', '--format', '{{.Name}}', 'routing-service')).Trim()
if ($routingName -eq '') { throw 'The development routing service is not running (docker compose up routing-service)' }
$routingCapped = $false

$token = Join-Path $repository $TokenFile
$quoted = { param($arguments) , @($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) }
$common = @('--url', $Url, '--metro', $Metro, '--time-zone', $TimeZone, '--area', $Area, '--scenario', 'daily',
    '--cost-seconds', "$CostSeconds", '--fairness-seconds', "$FairnessSeconds", '--appointments', "$Appointments", '--duration-s', '3600')
$index = 0
$skipped = 0
$previous = @{ SCHEDULER_IMAGE = $env:SCHEDULER_IMAGE; SCHEDULER_PULL_POLICY = $env:SCHEDULER_PULL_POLICY; SCHEDULER_STUDY_RECEIPTS = $env:SCHEDULER_STUDY_RECEIPTS; TIMEFOLD_LOG_LEVEL = $env:TIMEFOLD_LOG_LEVEL }
Push-Location $kit
try {
    & $docker @('compose', '-f', $devCompose, '-f', $routingOverride, 'up', '-d', '--no-deps', '--no-build', 'routing-service') (Join-Path $runs 'routing-cap.log')
    $routingCapped = $true
    foreach ($cell in $cells) {
        $index++
        $slug = & $slugOf $cell
        if ($null -ne (& $reportOf $cell)) { $skipped++; continue }
        & $useImage $cell.image
        if (-not (Test-Path -LiteralPath $token)) {
            $output = & $dockerOutput (@('compose') + $rehearsal + @('run', '--rm', 'scheduler-admin', '--command=issue-token', 'loadtest', 'Load test', 'stack verification'))
            $value = [regex]::Match($output, '(?m)^token: (wfs_\S+)').Groups[1].Value
            if ($value -eq '') { throw "Could not issue the study's token`n$output" }
            New-Item -ItemType Directory -Force (Split-Path $token -Parent) | Out-Null
            Set-Content -LiteralPath $token -Value $value -Encoding ascii -NoNewline
            Write-Output "Issued the study's token into $token"
        }
        $directory = Join-Path $runs $slug
        New-Item -ItemType Directory -Force $directory | Out-Null
        Get-ChildItem -LiteralPath $directory -File | Where-Object { $_.Extension -in '.json', '.md' } | Remove-Item -Force
        $daily = & $dailyContainers
        $arguments = @('tsx', 'load.ts') + $common + @('--token-file', $token, '--concurrency', "$($cell.planners)", '--daily-mode', $cell.mode,
            '--daily-proposals', "$($cell.proposals)", '--daily-technicians', "$($cell.fleet)", '--seed', "$($cell.seed)",
            '--scheduler-log', ($daily -join ','), '--docker', ((@($daily) + @($routingName)) -join ','), '--metrics-url', $MetricsUrl,
            '--label', "$slug $($images[$cell.image].commit.Substring(0, 12))", '--out', $directory)
        Write-Output "[$index/$($cells.Count)] $slug"
        $process = Start-Process -FilePath 'npx.cmd' -ArgumentList (& $quoted $arguments) -NoNewWindow -PassThru `
            -RedirectStandardOutput (Join-Path $directory 'run.log') -RedirectStandardError (Join-Path $directory 'run.err.log')
        # Windows PowerShell reports a started process's exit code only if its handle was read while it ran.
        $null = $process.Handle
        $process.WaitForExit()
        # Exit 1 is a run with breaches, which the report records; a crash also exits 1, so the report is required.
        if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 1) { throw "Run failed: $slug (see $directory)" }
        if ($null -eq (& $reportOf $cell)) { throw "Run wrote no report: $slug (see $directory/run.err.log)" }
    }
} finally {
    Pop-Location
    $ErrorActionPreference = 'Continue'
    foreach ($name in @($previous.Keys)) {
        if ($null -eq $previous[$name]) { Remove-Item -Path "env:$name" -ErrorAction SilentlyContinue } else { Set-Item -Path "env:$name" -Value $previous[$name] } }
    if ($null -ne $running) {
        Write-Output 'Stopping the study stack; bring the rehearsal back with its own image as deploy/vm/README.md says'
        & docker compose @rehearsal down *> (Join-Path $runs 'down.log')
    }
    if ($routingCapped) { & docker compose -f $devCompose up -d --no-deps --no-build routing-service *> (Join-Path $runs 'routing-restore.log') }
    $ErrorActionPreference = 'Stop'
}
Write-Output "Ran $($cells.Count - $skipped) runs, skipped $skipped already finished"

# The evidence: every run's report with what the run was. A run without receipts, or a receipt the kit could not
# pair with Timefold's speeds, is a broken instrument: the evidence is refused rather than written partly blank.
$entries = @()
$problems = @()
foreach ($cell in $cells) {
    $file = & $reportOf $cell
    if ($null -eq $file) { $problems += "$(& $slugOf $cell): no report"; continue }
    $report = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    if ($null -eq $report.study) { $problems += "$(& $slugOf $cell): no study receipts" }
    elseif ($report.study.summary.receipts -lt $cell.proposals) { $problems += "$(& $slugOf $cell): $($report.study.summary.receipts) receipts for $($cell.proposals) proposals" }
    elseif ($report.study.summary.unpairedSpeeds -gt 0) { $problems += "$(& $slugOf $cell): $($report.study.summary.unpairedSpeeds) receipts without per-phase speeds" }
    $entries += [ordered]@{ step = $cell.step; image = $cell.image; commit = $images[$cell.image].commit; fleet = $cell.fleet; seed = $cell.seed
        planners = $cell.planners; mode = $cell.mode; proposals = $cell.proposals; report = $report }
}
if ($problems.Count -gt 0) { throw "Runs the evidence cannot use (remove their directories under $runs and run again): $($problems -join '; ')" }
[ordered]@{
    study = 'Stack verification: the same daily proposals on images built from two commits, on the VM rehearsal through the public API'
    stack = 'deploy/vm rehearsal (proxy, booking and daily pools, VM compute caps; the development routing service capped to the VM partition), shipped routing and admission defaults, embedded calculation'
    images = $images
    protocol = "Step 1: 1 planner, synchronous, fleets $($fleetList -join ', ') technicians x $Appointments visits, points seeds $($seedList -join ', '), $Proposals proposals each. Step 2: $AsyncPlanners planners queuing (respond-async), $AsyncFleet technicians, seed $AsyncSeed, $AsyncProposals proposals. Search $CostSeconds s cost + $FairnessSeconds s fairness. Roads cold at each run's start, never warmed. Order: step 1 before then after, step 2 after then before. Daily pool with SCHEDULER_STUDY_RECEIPTS=true and TIMEFOLD_LOG_LEVEL=INFO; the kit reads each receipt and pairs it with its request's Timefold phase speeds."
    runnerRevision = (git -C $repository rev-parse HEAD).Trim()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    containersVerified = $verified
    runs = $entries
} | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $evidence -Encoding utf8
Write-Output "Evidence: $evidence (reports and logs in $runs)"
