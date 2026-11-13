# Backtest replay through the public API: the rehearsal stack (deploy/vm with rehearsal.override.yml, shipped routing and
# admission defaults) on an image built from -Revision, answering each imported day's api-request.json
# (infra/backtest_import.py) on its shifted service date. infra/backtest_replay.py posts, polls and stores the answers
# under <RunDir>/api/<day>/, then scores every 201 proposal over the day's sealed facts with the benchmark jar so the
# API's plan and the harness's are read by the same scorer; the summary pairs them.
# The new metro is served by the map preview pair (docker-compose.map-preview.yml, routing-preview on 127.0.0.1:18001),
# which must be up on the study's map version, and the rehearsal env must list it in ROUTING_METRO_URLS.
# About an hour for 120 days at concurrency 2; the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-backtest-replay.ps1 -RunDir experiments/runs/<run-id> -Revision <sha>
# The noise pass replays the same days again under another repetition: -Select noise:50 -Rep 2
param(
    [Parameter(Mandatory)][string]$RunDir,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision,
    # Which days: sample:<n> (a stratified sample of the test split), noise:<n>, test, calibration, smoke, all or dates.
    [string]$Select = 'sample:120',
    [ValidateRange(1, 10)][int]$Rep = 1,
    [ValidateRange(1, 4)][int]$Concurrency = 2,
    [string]$Url = 'http://127.0.0.1:18080',
    [string]$PreviewUrl = 'http://127.0.0.1:18001',
    [string]$RehearsalEnv = 'deploy/vm/rehearsal.env',
    # The campaign-benchmark jar that scores the answers; by default the harness tree's build.
    [string]$Jar = 'C:\Projects\waterflex-worktrees\backtest-tree\campaign-benchmark\target\campaign-benchmark-0.1.0-SNAPSHOT.jar',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Where the image's worktree is checked out for the build (outside OneDrive).
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees',
    [ValidateRange(60, 3600)][int]$TimeoutSeconds = 900,
    # The daily replica records JFR into <RunDir>/api-profiles unless this is given.
    [switch]$NoProfiles,
    # Leave the stack up afterwards (the next pass skips the build and the start).
    [switch]$KeepStack,
    # Post nothing; print the plan and check the stack's inputs.
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'study-token.psm1') -Force
$repository = Split-Path $PSScriptRoot -Parent
$vmCompose = Join-Path $repository 'deploy/vm/docker-compose.yml'
$rehearsalOverride = Join-Path $repository 'deploy/vm/rehearsal.override.yml'
$envFile = Join-Path $repository $RehearsalEnv
if (-not (Test-Path -LiteralPath $envFile)) { throw "No rehearsal env file at $envFile (see deploy/vm/README.md, Local rehearsal)" }
$run = if ([IO.Path]::IsPathRooted($RunDir)) { $RunDir } else { Join-Path $repository $RunDir }
if (-not (Test-Path -LiteralPath (Join-Path $run 'index.json'))) { throw "No importer index at $run (run infra/backtest_import.py build first)" }
$index = Get-Content -LiteralPath (Join-Path $run 'index.json') -Raw | ConvertFrom-Json
$config = Get-Content -LiteralPath (Join-Path $run 'study-config.json') -Raw | ConvertFrom-Json
if ($index.routingIdentity -like 'stub-*') { throw "The run at $run was imported with the stub road model; import it against the routing service first" }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "No JDK at $JavaHome" }
if (-not (Test-Path -LiteralPath $Jar)) { throw "No benchmark jar at $Jar (run infra/run-backtest-study.ps1 first, or pass -Jar)" }
$logs = Join-Path $run 'api-logs'
New-Item -ItemType Directory -Force $logs | Out-Null

# JSON the engine and Python read: UTF-8 without the byte-order mark Windows PowerShell's -Encoding utf8 writes.
function Write-Json($value, $path, $depth) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth $depth), (New-Object Text.UTF8Encoding $false))
}
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
$status = { param($url)
    try { return [int](Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 5).StatusCode }
    catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return 0 } } }

# The road model the stack will route with must be the one the datasets were frozen on, else the API's plan is timed
# on other legs than the harness's and the agreement table compares two instruments.
$preview = try { Invoke-RestMethod "$PreviewUrl/health" -TimeoutSec 10 } catch { throw "The map preview routing service is not answering at $PreviewUrl (docker compose -f docker-compose.map-preview.yml up -d routing-preview)" }
if (-not $preview.ready) { throw "The map preview routing service at $PreviewUrl is not ready" }
if ($preview.routingIdentity -ne $index.routingIdentity) { throw "The preview routes with $($preview.routingIdentity); the run was imported on $($index.routingIdentity)" }
$metro = $config.metroId
$metroLine = (Get-Content -LiteralPath $envFile | Where-Object { $_ -like 'ROUTING_METRO_URLS=*' } | Select-Object -First 1)
if ($null -eq $metroLine -or $metroLine -notmatch "(^|[=,])$([regex]::Escape($metro))=") {
    throw "The rehearsal env does not route $metro; add it to ROUTING_METRO_URLS in $envFile (for example ,$metro=http://host.docker.internal:18001)" }
# The replicas report ready only while every listed metro's routing answers (HealthController), so each one is probed
# from the host. The development routing-service (8001, Omaha) is started for the replay when it is down, and stopped
# by the pass that takes the stack down; any other metro that does not answer stops the replay here.
$devCompose = Join-Path $repository 'docker-compose.yml'
$routingMarker = Join-Path $logs 'routing-service-started-by-replay'
$metroUrls = [ordered]@{}
foreach ($pair in ($metroLine -replace '^ROUTING_METRO_URLS=', '') -split ',') {
    $name, $address = $pair -split '=', 2
    $metroUrls[$name.Trim()] = ($address.Trim() -replace 'host\.docker\.internal', '127.0.0.1').TrimEnd('/') }
$routingReady = { param($address) try { [bool](Invoke-RestMethod "$address/health" -TimeoutSec 5).ready } catch { $false } }
$devRoutingDown = $false
foreach ($name in @($metroUrls.Keys)) {
    if (& $routingReady $metroUrls[$name]) { continue }
    if ($metroUrls[$name] -match ':8001$' -and $name -ne $metro) { $devRoutingDown = $true; continue }
    throw "Routing for $name does not answer at $($metroUrls[$name])/health; the rehearsal replicas would never report ready" }

# The image: the commit in its own worktree, built once under its own tag.
$sha = (git -C $repository rev-parse --verify "$Revision^{commit}" 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $sha) { throw "Not a commit: $Revision" }
$sha = $sha.Trim()
$tag = "waterflex-scheduler-engine:backtest-$($sha.Substring(0, 12))"
$existing = (& $dockerOutput @('images', '-q', $tag)).Trim()
if ($existing -eq '' -and -not $DryRun) {
    New-Item -ItemType Directory -Force $TreeRoot | Out-Null
    $tree = Join-Path $TreeRoot "backtest-image-$($sha.Substring(0, 12))"
    if (Test-Path -LiteralPath $tree) { git -C $repository worktree remove --force $tree | Out-Null }
    # The image builds in Linux: the tree keeps the repository's LF endings (mvnw), whatever core.autocrlf says here.
    git -C $repository -c core.autocrlf=false worktree add --detach $tree $sha | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not check out $sha into $tree" }
    try {
        Write-Output "Building $tag from $sha"
        & $docker @('build', '-t', $tag, '-f', (Join-Path $tree 'scheduler-service/Dockerfile'), $tree) (Join-Path $logs 'build.log')
    } finally { git -C $repository worktree remove --force $tree | Out-Null }
}
$imageId = if ($DryRun -and $existing -eq '') { 'not built (dry run)' } else { (& $dockerOutput @('images', '--no-trunc', '-q', $tag)).Trim() }

# With profiles, the daily replica's /profiles is the run's api-profiles folder, through one more override file.
$profiles = -not $NoProfiles
$profilesDirectory = Join-Path $run 'api-profiles'
$profilesOverride = Join-Path $logs 'profiles-mount.yml'
if ($profiles) {
    New-Item -ItemType Directory -Force $profilesDirectory | Out-Null
    # Long syntax: a Windows drive letter's colon would be read as the short syntax's separator.
    @('services:', '  scheduler-daily:', '    volumes:', '      - type: bind', "        source: '$($profilesDirectory.Replace('\', '/'))'", '        target: /profiles') |
        Set-Content -LiteralPath $profilesOverride -Encoding ascii
}
$rehearsal = @('-f', $vmCompose, '-f', $rehearsalOverride) + $(if ($profiles) { @('-f', $profilesOverride) } else { @() }) + @('--env-file', $envFile)
$dailyContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-daily'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$bookingContainers = { , @((& $dockerOutput (@('compose') + $rehearsal + @('ps', '--format', '{{.Name}}', 'scheduler-booking'))) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() }) }
$healthy = { param($ids)
    foreach ($id in $ids) { if ((& $dockerOutput @('inspect', '--format', '{{.State.Health.Status}}', $id)).Trim() -ne 'healthy') { return $false } }
    $ids.Count -gt 0 }
# Both pools healthy and reached through the proxy: the booking pool on /health, the daily pool on a request status route.
$health = { param($seconds)
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        try {
            $daily = & $dailyContainers; $booking = & $bookingContainers
            $dailyStatus = & $status "$Url/api/v1/requests/study-health-probe"
            if ((& $healthy $daily) -and (& $healthy $booking) -and (& $status "$Url/health") -eq 200 -and $dailyStatus -ge 200 -and $dailyStatus -lt 500) { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    $false }

$days = @()
$ErrorActionPreference = 'Continue'
$selected = & python (Join-Path $repository 'infra/backtest_summary.py') 'days' '--run' $run '--select' $Select 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw "Day selection failed: $selected" }
$days = @($selected | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2}$' })
if ($days.Count -eq 0) { throw "No days selected by $Select" }
Write-Output "Replaying $($days.Count) days ($Select) as repetition $Rep through $Url on $tag; routing $metro via $PreviewUrl ($($index.routingIdentity))"
if ($DryRun) { Write-Output ($days -join ', '); return }

$started = $false
$tokenId = $null
$previous = @{ SCHEDULER_IMAGE = $env:SCHEDULER_IMAGE; SCHEDULER_PULL_POLICY = $env:SCHEDULER_PULL_POLICY; SCHEDULER_STUDY_RECEIPTS = $env:SCHEDULER_STUDY_RECEIPTS
    SCHEDULER_STUDY_PROFILES = $env:SCHEDULER_STUDY_PROFILES; TIMEFOLD_LOG_LEVEL = $env:TIMEFOLD_LOG_LEVEL }
$since = [DateTime]::UtcNow.ToString('o')
try {
    $env:SCHEDULER_IMAGE = $tag; $env:SCHEDULER_PULL_POLICY = 'never'
    $env:SCHEDULER_STUDY_RECEIPTS = 'true'; $env:TIMEFOLD_LOG_LEVEL = 'INFO'
    $env:SCHEDULER_STUDY_PROFILES = if ($profiles) { '/profiles' } else { '' }
    if ($devRoutingDown) {
        Write-Output 'Starting the development routing-service (Omaha) so the rehearsal replicas can report ready'
        & $docker @('compose', '-f', $devCompose, 'up', '-d', '--no-deps', '--no-build', 'routing-service') (Join-Path $logs 'routing-start.log')
        Set-Content -LiteralPath $routingMarker -Value $since -Encoding ascii
        $until = (Get-Date).AddMinutes(10)
        while (-not (& $routingReady 'http://127.0.0.1:8001') -and (Get-Date) -lt $until) { Start-Sleep -Seconds 5 }
        if (-not (& $routingReady 'http://127.0.0.1:8001')) { throw "The development routing-service did not come up (see $logs/routing-start.log)" }
    }
    & $docker (@('compose') + $rehearsal + @('up', '-d', '--no-build', '--remove-orphans')) (Join-Path $logs 'up.log')
    $started = $true
    if (-not (& $health 300)) {
        # The replicas' own words on why they are not ready, kept before the stack comes down.
        $ErrorActionPreference = 'Continue'
        & docker compose @rehearsal logs --no-color --tail 120 scheduler-daily scheduler-booking *> (Join-Path $logs 'not-ready.log')
        $ErrorActionPreference = 'Stop'
        throw "The stack did not come up on $tag (see $logs/up.log and not-ready.log)" }
    $daily = & $dailyContainers
    $verified = @()
    foreach ($id in @($daily) + @(& $bookingContainers)) {
        $inspect = (& $dockerOutput @('inspect', $id)) | ConvertFrom-Json
        if ($inspect[0].Image -ne $imageId) { throw "Container $id does not run $tag" }
        $verified += [ordered]@{ container = $id; imageId = $inspect[0].Image; nanoCpus = $inspect[0].HostConfig.NanoCpus; memory = $inspect[0].HostConfig.Memory }
    }
    # This pass's token lives only in this process's environment and is revoked when the pass ends (study-token.psm1).
    $tokenId = New-StudyToken -Compose (@('compose') + $rehearsal) -Tenant 'backtest' -Name 'Backtest replay' -Label $index.runId -Variable 'SCHEDULER_BACKTEST_TOKEN'
    $manifest = [ordered]@{ runId = $index.runId; revision = $sha; image = $tag; imageId = $imageId; url = $Url; metro = $metro; preview = $PreviewUrl
        routingIdentity = $index.routingIdentity; select = $Select; days = $days.Count; rep = $Rep; concurrency = $Concurrency; profiles = $profiles
        containers = $verified; startedAt = $since }
    Write-Json $manifest (Join-Path $logs "manifest-rep$Rep-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')).json") 6
    $ErrorActionPreference = 'Continue'
    & python -X utf8 (Join-Path $repository 'infra/backtest_replay.py') replay --run $run --url $Url --token-env SCHEDULER_BACKTEST_TOKEN --select $Select --rep $Rep --concurrency $Concurrency --timeout-s $TimeoutSeconds 2>&1 |
        Tee-Object -FilePath (Join-Path $logs "replay-rep$Rep.log")
    $replayCode = $LASTEXITCODE
    foreach ($id in $daily) { & docker logs --timestamps --since $since $id 2>&1 | ForEach-Object { "$_" } | Set-Content -LiteralPath (Join-Path $logs "scheduler-daily-rep$Rep-$id.log") -Encoding utf8 }
    $ErrorActionPreference = 'Stop'
    if ($replayCode -ne 0) { throw "The replay failed (see $logs/replay-rep$Rep.log)" }
} finally {
    $ErrorActionPreference = 'Continue'
    foreach ($name in @($previous.Keys)) {
        if ($null -eq $previous[$name]) { Remove-Item -Path "env:$name" -ErrorAction SilentlyContinue } else { Set-Item -Path "env:$name" -Value $previous[$name] } }
    if ($null -ne $tokenId) { Remove-StudyToken -Compose (@('compose') + $rehearsal) -Id $tokenId -Variable 'SCHEDULER_BACKTEST_TOKEN' -Log (Join-Path $logs 'revoke.log') }
    if ($started -and -not $KeepStack) {
        Write-Output 'Stopping the study stack; bring the rehearsal back with its own image as deploy/vm/README.md says'
        & docker compose @rehearsal down *> (Join-Path $logs 'down.log')
    }
    # The routing-service a replay pass started (this one or an earlier -KeepStack pass) stops with the stack.
    if (-not $KeepStack -and (Test-Path -LiteralPath $routingMarker)) {
        & docker compose -f $devCompose stop routing-service *> (Join-Path $logs 'routing-stop.log')
        Remove-Item -LiteralPath $routingMarker -Force
    }
    $ErrorActionPreference = 'Stop'
}

# The answers scored over the frozen facts, by the same jar and scorer as the harness rows.
$ErrorActionPreference = 'Continue'
& python -X utf8 (Join-Path $repository 'infra/backtest_replay.py') score --run $run --jar $Jar --java $java --select $Select --rep $Rep 2>&1 | Tee-Object -FilePath (Join-Path $logs "score-rep$Rep.log")
$scoreCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($scoreCode -ne 0) { throw "Scoring the answers failed (see $logs/score-rep$Rep.log)" }
Write-Output "Replay done; summarize with: python infra/backtest_summary.py summarize --run $run --out research/backtest-<client>-<date>/"
