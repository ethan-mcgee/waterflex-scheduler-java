# Part 2 of a backtest (research/backtest-paso-robles-2026-11/simulation-plan.md, "Part 2: the product simulated"):
# the product booking every request and optimizing every night through the public API of the VM rehearsal stack
# (deploy/vm with rehearsal.override.yml, 127.0.0.1:18080), with an image built from -Revision, two daily replicas
# (decision 4) and every other setting shipped. The study's metro routes through the development stack's routing-preview.
#
# Steps: freeze and build the tree of -Revision (the Python and the scoring jar), build the scheduler image of
# -Revision, plan the simulation unless it exists, bring the stack up, issue one token per segment (kept in this
# process's environment only, revoked at the end), simulate, export, score with the same scorer as Part 1, compare.
# A rerun resumes: the simulation keeps its plan and checkpoints, the tenants are the plan's, and the API replays
# what each tenant was already answered.
#
#   -Smoke        one segment for one simulated week (Claude runs this one)
#   -Calibration  the busiest 2024 month: run A alone, then run B (four copies at once); applies the plan's bars
#   -Full         the whole history in -Segments segments with a -WarmupWeeks warm-up (take both from the calibration)
#   -DryRun       checks the inputs and prints the plan; builds, starts and writes nothing
#
# Four options of the plan (smoke and full; a plan that exists must already have them, or the run is refused):
#   -NoWeekendWork        the product's roster leaves out Saturday and Sunday technician-days
#   -OvernightInFlight n  up to n of a night's proposals wait on the API at once (the daily pool solves four at a time)
#   -OfferLimit n         each search offers up to n two-hour windows (1 to the API's 4)
#   -ClientPick p         the window the client takes: first (the one offer of -OfferLimit 1) or random (one of those shown)
#
#   powershell -ExecutionPolicy Bypass -File infra/run-backtest-simulation.ps1 -Smoke -RunDir experiments/runs/<run-id> -Revision <sha> -ReportDir research/<study>
param(
    [switch]$Smoke,
    [switch]$Calibration,
    [switch]$Full,
    [switch]$DryRun,
    [Parameter(Mandatory)][string]$RunDir,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision,
    [Parameter(Mandatory)][string]$ReportDir,
    # The simulation's directory name under <RunDir>/part2/simulations (default: the mode); it also names the tenants, so keep it short.
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,30}$')][string]$Name = '',
    [ValidateRange(1, 8)][int]$Segments = 4,
    [ValidateRange(1, 8)][int]$WarmupWeeks = 3,
    [switch]$NoWeekendWork,
    [ValidateRange(1, 8)][int]$OvernightInFlight = 1,
    [ValidateRange(1, 4)][int]$OfferLimit = 1,
    [ValidateSet('first', 'random')][string]$ClientPick = 'first',
    # The simulated weeks of a smoke.
    [ValidateRange(1, 8)][int]$SmokeWeeks = 1,
    # The comparison's directory under -ReportDir: <ReportName> for a full run, <ReportName>-smoke-<Name> for a smoke.
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,30}$')][string]$ReportName = 'part2',
    # The comparison's H1 (infra/backtest_compare.py's own title when empty), for a backtest other than the first.
    [string]$Title = '',
    [string]$Url = 'http://127.0.0.1:18080',
    [string]$RoutingUrl = 'http://127.0.0.1:18001',
    [string]$RehearsalEnv = 'deploy/vm/rehearsal.env',
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees\backtest-tree',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Without flight recordings of the daily replicas (they are on by default, about 1 percent overhead).
    [switch]$NoProfiles
)
$ErrorActionPreference = 'Stop'
$modes = @($Smoke, $Calibration, $Full) | Where-Object { $_ }
if ($modes.Count -ne 1) { throw 'Choose exactly one of -Smoke, -Calibration or -Full' }
$mode = if ($Smoke) { 'smoke' } elseif ($Calibration) { 'calibration' } else { 'full' }
if ($Calibration -and ($NoWeekendWork -or $OvernightInFlight -ne 1 -or $OfferLimit -ne 1 -or $ClientPick -ne 'first')) {
    throw 'The calibration plans take none of -NoWeekendWork, -OvernightInFlight, -OfferLimit or -ClientPick'
}
if ($ClientPick -eq 'first' -and $OfferLimit -ne 1) { throw "-ClientPick first takes -OfferLimit 1: the API sorts several offers by window start, so the first is not the least cost" }
$planOptions = @('--overnight-in-flight', "$OvernightInFlight", '--offer-limit', "$OfferLimit", '--client-pick', $ClientPick)
if ($NoWeekendWork) { $planOptions += '--no-weekend-work' }

function Invoke-Native($program, [string[]]$arguments, $log) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $program @arguments *> $log } finally { $ErrorActionPreference = $previous }
    return $LASTEXITCODE
}
function Invoke-Output($program, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $program @arguments 2>&1 | Out-String } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$program $($arguments -join ' ') failed`n$output" }
    return $output
}

$repository = Split-Path $PSScriptRoot -Parent
$common = (& git -C $repository rev-parse --path-format=absolute --git-common-dir).Trim()
$main = Split-Path $common -Parent
$run = if ([IO.Path]::IsPathRooted($RunDir)) { $RunDir } else { Join-Path $main $RunDir }
$reports = if ([IO.Path]::IsPathRooted($ReportDir)) { $ReportDir } else { Join-Path $main $ReportDir }
$inputs = Join-Path $run 'part2\inputs.json'
if (-not (Test-Path -LiteralPath $inputs)) { throw "No Part 2 inputs at $inputs (backtest_import.py inputs)" }
$identity = (Get-Content -LiteralPath $inputs -Raw | ConvertFrom-Json).routingIdentity
$envFile = Join-Path $main $RehearsalEnv
if (-not (Test-Path -LiteralPath $envFile)) { throw "No rehearsal env file at $envFile (see deploy/vm/README.md, Local rehearsal)" }
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envFile) { if ($line -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] } }
$metro = (Get-Content -LiteralPath (Join-Path $run 'study-config.json') -Raw | ConvertFrom-Json).metroId
$route = @($settings['ROUTING_METRO_URLS'] -split ',' | Where-Object { $_ -like "$metro=*" })
if ($route.Count -ne 1) { throw "$RehearsalEnv routes no $metro (ROUTING_METRO_URLS); see deploy/vm/README.md, Another metro" }
# Each replica's readiness probes every metro it routes, and the env file's other metros (metro-omaha on the development
# stack's routing) need not be running; the stack routes only the metro under study.
$metroRoutes = $route[0]
try { $health = Invoke-RestMethod "$RoutingUrl/health" -TimeoutSec 10 } catch { throw "routing-preview does not answer at $RoutingUrl (docker compose -f docker-compose.yml -f docker-compose.map-preview.yml up -d --no-deps nominatim-preview routing-preview)" }
if (-not $health.ready -or $health.routingIdentity -ne $identity) { throw "routing-preview serves $($health.routingIdentity), the run was imported on $identity" }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "No JDK at $JavaHome" }

$today = (Get-Date).ToString('yyyy-MM-dd')
# Fixed by default, so a rerun on another day resumes the same simulation; a new attempt takes -Name.
if ($Name -eq '') { $Name = $mode }
$simulations = Join-Path $run 'part2\simulations'
$sha = $Revision.Substring(0, 12)
$image = "waterflex-scheduler-engine:backtest-$sha"
Write-Output "Part 2 $mode of $run at $Revision; simulation $Name; image $image; routing $identity"
if ($DryRun) {
    if ($Calibration) { Write-Output "calibration month: $(& python (Join-Path $repository 'infra/backtest_simulate.py') busiest --run $run --year 2024)" }
    Write-Output "segments $Segments, warm-up $WarmupWeeks weeks (full); weekend work $(-not $NoWeekendWork); overnight proposals in flight $OvernightInFlight; offer limit $OfferLimit; client pick $ClientPick; smoke weeks $SmokeWeeks"
    Write-Output "reports to $(Join-Path $reports $(if ($Smoke) { "$ReportName-smoke-$Name" } else { $ReportName }))"
    return
}

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Backtest -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Backtest.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# The frozen tree of the revision: its Python runs the study and its jar scores it (as infra/run-backtest-dispatcher.ps1).
$logs = Join-Path $simulations "$Name-logs"
New-Item -ItemType Directory -Force $logs | Out-Null
$jar = Join-Path $TreeRoot 'campaign-benchmark\target\campaign-benchmark-0.1.0-SNAPSHOT.jar'
$built = Join-Path $TreeRoot 'campaign-benchmark\target\backtest-built.txt'
if ((Test-Path -LiteralPath $built) -and ((Get-Content -LiteralPath $built -Raw).Trim() -eq $Revision) -and (Test-Path -LiteralPath $jar)) {
    Write-Output "reusing the build of $Revision in $TreeRoot"
} else {
    if (Test-Path -LiteralPath $TreeRoot) { throw "$TreeRoot exists and is not a build of $Revision; remove it (git worktree remove) or choose another -TreeRoot" }
    if ((Invoke-Native git @('-C', $main, 'worktree', 'add', '--detach', $TreeRoot, $Revision) (Join-Path $logs 'worktree.log')) -ne 0) { throw "git worktree add failed (see $logs\worktree.log)" }
    $env:JAVA_HOME = $JavaHome
    $code = Invoke-Native (Join-Path $TreeRoot 'mvnw.cmd') @('-q', '-f', (Join-Path $TreeRoot 'pom.xml'), '-Pcampaign-benchmark', '-pl', 'campaign-benchmark', '-am', '-DskipTests', 'package') (Join-Path $logs 'build.log')
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw "The build failed (see $logs\build.log)" }
    Set-Content -LiteralPath $built -Value $Revision -Encoding ascii
}
if ((& git -C $TreeRoot rev-parse HEAD).Trim() -ne $Revision) { throw "$TreeRoot is not at $Revision" }
$tools = Join-Path $TreeRoot 'infra'
function Invoke-Tool([string]$script, [string[]]$arguments, [string]$log) {
    $code = Invoke-Native python (@((Join-Path $tools $script)) + $arguments) $log
    if ($code -ne 0) { throw "$script $($arguments -join ' ') failed (see $log)" }
    Get-Content -LiteralPath $log -Raw
}

# The scheduler image of the revision, built once in its own worktree outside OneDrive with the repository's LF endings.
if ((Invoke-Output docker @('images', '-q', $image)).Trim() -eq '') {
    $imageTree = "C:\Projects\waterflex-worktrees\image-$sha"
    if (Test-Path -LiteralPath $imageTree) { & git -C $main worktree remove --force $imageTree | Out-Null }
    & git -C $main -c core.autocrlf=false worktree add --detach $imageTree $Revision | Out-Null
    try {
        Write-Output "building $image"
        if ((Invoke-Native docker @('build', '-t', $image, '-f', (Join-Path $imageTree 'scheduler-service/Dockerfile'), $imageTree) (Join-Path $logs 'image.log')) -ne 0) { throw "The image build failed (see $logs\image.log)" }
    } finally { & git -C $main worktree remove --force $imageTree | Out-Null }
}
$imageId = (Invoke-Output docker @('images', '--no-trunc', '-q', $image)).Trim()

# The simulations of the mode, planned once; a rerun resumes the plan it finds.
# A plan that exists is run as it was written, so one written with other options than this command's is refused
# rather than resumed under the wrong name (a version 1 plan has weekend work and one proposal at a time; versions 1 and
# 2 ask for one offer, which the client takes).
function Assert-PlanOptions([string]$directory) {
    $config = Get-Content -LiteralPath (Join-Path $directory 'simulation.json') -Raw | ConvertFrom-Json
    $weekendWork = if ($config.version -eq 1) { $true } else { $config.weekendWork }
    $inFlight = if ($config.version -eq 1) { 1 } else { $config.overnightInFlight }
    $offers = $config.settings.offerLimit
    $pick = if ($config.version -lt 3) { 'first' } else { $config.clientPick }
    if ($weekendWork -isnot [bool] -or $weekendWork -ne (-not $NoWeekendWork) -or $inFlight -ne $OvernightInFlight -or $offers -ne $OfferLimit -or $pick -ne $ClientPick) {
        throw "The plan in $directory has weekendWork '$weekendWork', overnightInFlight '$inFlight', offerLimit '$offers' and clientPick '$pick'; this command asks for $(-not $NoWeekendWork), $OvernightInFlight, $OfferLimit and $ClientPick. Choose another -Name for a new plan."
    }
}
$plans = @()
if ($Smoke) {
    $directory = Join-Path $simulations $Name
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'simulation.json'))) {
        $first = ((Get-Content -LiteralPath (Join-Path $run 'part2\requests.json') -Raw | ConvertFrom-Json).requests | ForEach-Object { $_.dispatcher.serviceDate } | Sort-Object | Select-Object -First 1)
        $monday = ([datetime]$first).AddDays(-(([int]([datetime]$first).DayOfWeek + 6) % 7)).ToString('yyyy-MM-dd')
        Invoke-Tool 'backtest_simulate.py' (@('plan', '--run', $run, '--out', $directory, '--segments', "$Segments", '--warmup-weeks', "$WarmupWeeks", '--only-first', '1',
            '--until', ([datetime]$monday).AddDays(7 * $SmokeWeeks - 1).ToString('yyyy-MM-dd'), '--today', $today) + $planOptions) (Join-Path $logs 'plan.log') | Out-Null
    }
    Assert-PlanOptions $directory
    $plans += @{ directory = $directory; label = 'smoke'; partial = $true; copies = @($null) }
} elseif ($Calibration) {
    $month = (Invoke-Tool 'backtest_simulate.py' @('busiest', '--run', $run, '--year', '2024') (Join-Path $logs 'busiest.log')) | ConvertFrom-Json
    foreach ($pair in @(@('a', 'a:3'), @('b', 'b1:3,b2:3,c1:6,c2:6'))) {
        $directory = Join-Path $simulations "$Name-$($pair[0])"
        if (-not (Test-Path -LiteralPath (Join-Path $directory 'simulation.json'))) {
            Invoke-Tool 'backtest_simulate.py' @('window', '--run', $run, '--out', $directory, '--first', $month.first, '--end', $month.end, '--copies', $pair[1], '--today', $today) (Join-Path $logs "plan-$($pair[0]).log") | Out-Null
        }
        $plans += @{ directory = $directory; label = "calibration-$($pair[0])"; partial = $false; copies = @($pair[1] -split ',' | ForEach-Object { ($_ -split ':')[0] }) }
    }
} else {
    $directory = Join-Path $simulations $Name
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'simulation.json'))) {
        Invoke-Tool 'backtest_simulate.py' (@('plan', '--run', $run, '--out', $directory, '--segments', "$Segments", '--warmup-weeks', "$WarmupWeeks", '--today', $today) + $planOptions) (Join-Path $logs 'plan.log') | Out-Null
    }
    Assert-PlanOptions $directory
    $plans += @{ directory = $directory; label = 'full'; partial = $false; copies = @($null) }
}

# The stack on the revision's image, every replica checked to run it.
$compose = @('compose', '-f', (Join-Path $main 'deploy/vm/docker-compose.yml'), '-f', (Join-Path $main 'deploy/vm/rehearsal.override.yml'))
# rehearsal.override.yml publishes the daily pool's management port on one fixed loopback port, which only one replica
# can bind; the simulation reads no replica metrics, so the two daily replicas publish nothing.
$unpublished = Join-Path $logs 'daily-unpublished.yml'
@('services:', '  scheduler-daily:', '    ports: !reset []') | Set-Content -LiteralPath $unpublished -Encoding ascii
$compose += @('-f', $unpublished)
$profiles = -not $NoProfiles
if ($profiles) {
    $profileDirectory = Join-Path $simulations "$Name-profiles"
    New-Item -ItemType Directory -Force $profileDirectory | Out-Null
    $mount = Join-Path $logs 'profiles-mount.yml'
    @('services:', '  scheduler-daily:', '    volumes:', '      - type: bind', "        source: '$($profileDirectory.Replace('\', '/'))'", '        target: /profiles') | Set-Content -LiteralPath $mount -Encoding ascii
    $compose += @('-f', $mount)
}
$compose += @('--env-file', $envFile)
$saved = @{}
foreach ($key in @('SCHEDULER_IMAGE', 'SCHEDULER_PULL_POLICY', 'SCHEDULER_DAILY_REPLICAS', 'SCHEDULER_STUDY_PROFILES', 'ROUTING_AUTH_TOKEN', 'ROUTING_METRO_URLS')) { $saved[$key] = [Environment]::GetEnvironmentVariable($key) }
$env:SCHEDULER_IMAGE = $image; $env:SCHEDULER_PULL_POLICY = 'never'; $env:SCHEDULER_DAILY_REPLICAS = '2'; $env:ROUTING_METRO_URLS = $metroRoutes
$env:SCHEDULER_STUDY_PROFILES = if ($profiles) { '/profiles' } else { '' }
# The export reads new road legs from routing-preview with the development stack's routing token, from the env file.
$env:ROUTING_AUTH_TOKEN = $settings['ROUTING_AUTH_TOKEN']
$tokens = @()
$started = Get-Date
try {
    if ((Invoke-Native docker ($compose + @('up', '-d', '--no-build', '--remove-orphans')) (Join-Path $logs 'up.log')) -ne 0) { throw "The stack did not start (see $logs\up.log)" }
    $until = (Get-Date).AddSeconds(300)
    do {
        Start-Sleep -Seconds 3
        $containers = @((Invoke-Output docker ($compose + @('ps', '--format', '{{.Name}}', 'scheduler-daily', 'scheduler-booking'))) -split "`r?`n" | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })
        $states = @($containers | ForEach-Object { (Invoke-Output docker @('inspect', '--format', '{{.State.Health.Status}}', $_)).Trim() })
        $ready = $containers.Count -ge 3 -and @($states | Where-Object { $_ -ne 'healthy' }).Count -eq 0
        try { $ready = $ready -and (Invoke-WebRequest "$Url/health" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $ready = $false }
    } until ($ready -or (Get-Date) -gt $until)
    if (-not $ready) { throw "The stack did not become healthy within 300 s (see docker compose ps)" }
    $daily = @((Invoke-Output docker ($compose + @('ps', '--format', '{{.Name}}', 'scheduler-daily'))) -split "`r?`n" | Where-Object { $_.Trim() })
    if ($daily.Count -ne 2) { throw "Expected 2 daily replicas, found $($daily.Count)" }
    foreach ($id in $containers) {
        if ((Invoke-Output docker @('inspect', '--format', '{{.Image}}', $id)).Trim() -ne $imageId) { throw "Container $id does not run $image" }
    }
    Write-Output "stack up on ${image}: $($containers -join ', ')"

    foreach ($plan in $plans) {
        $config = Get-Content -LiteralPath (Join-Path $plan.directory 'simulation.json') -Raw | ConvertFrom-Json
        foreach ($segment in $config.segments) {
            $output = Invoke-Output docker ($compose + @('run', '--rm', 'scheduler-admin', '--command=issue-token', $segment.tenantId, "Backtest $($segment.name)", "backtest simulation $($config.simulationId)"))
            $value = [regex]::Match($output, '(?m)^token: (wfs_\S+)').Groups[1].Value
            $tokenId = [regex]::Match($output, '(?m)^id: (\S+)').Groups[1].Value
            if ($value -eq '' -or $tokenId -eq '') { throw "Could not issue a token for $($segment.tenantId)" }
            $tokens += $tokenId
            Set-Item -Path "env:$($segment.tokenEnv)" -Value $value
        }
        Write-Output "$(Get-Date -Format s) simulating $($plan.label) ($($config.segments.Count) segments)"
        Invoke-Tool 'backtest_simulate.py' @('run', '--config', (Join-Path $plan.directory 'simulation.json'), '--url', $Url) (Join-Path $logs "run-$($plan.label).log") | Out-Null
        foreach ($copy in $plan.copies) {
            $exportArguments = @('export', '--simulation', $plan.directory, '--roads', $RoutingUrl)
            $scoreArguments = @('score', '--simulation', $plan.directory, '--jar', $jar, '--java', $java)
            $compareArguments = @('compare', '--simulation', $plan.directory)
            if ($Title) { $compareArguments += @('--title', $Title) }
            $suffix = $plan.label
            if ($plan.partial) { $exportArguments += '--partial' }
            if ($null -ne $copy) { $exportArguments += @('--segment', $copy); $scoreArguments += @('--segment', $copy); $compareArguments += @('--segment', $copy); $suffix = "$($plan.label)-$copy" }
            Invoke-Tool 'backtest_export.py' $exportArguments (Join-Path $logs "export-$suffix.log") | Out-Null
            Invoke-Tool 'backtest_export.py' $scoreArguments (Join-Path $logs "score-$suffix.log") | Out-Null
            if (-not $Calibration) {
                $target = Join-Path $reports $(if ($Smoke) { "$ReportName-smoke-$Name" } else { $ReportName })
                Write-Output (Invoke-Tool 'backtest_compare.py' ($compareArguments + @('--out', $target)) (Join-Path $logs "compare-$suffix.log"))
            }
        }
    }
    if ($Calibration) {
        Write-Output (Invoke-Tool 'backtest_compare.py' @('calibration', '--alone', $plans[0].directory, '--together', $plans[1].directory, '--out', (Join-Path $reports 'calibration')) (Join-Path $logs 'calibration.log'))
    }
} finally {
    $ErrorActionPreference = 'Continue'
    foreach ($tokenId in $tokens) { & docker @($compose + @('run', '--rm', 'scheduler-admin', '--command=revoke-token', $tokenId)) *> (Join-Path $logs 'revoke.log') }
    foreach ($plan in $plans) {
        $config = Get-Content -LiteralPath (Join-Path $plan.directory 'simulation.json') -Raw | ConvertFrom-Json
        foreach ($segment in $config.segments) { Remove-Item -Path "env:$($segment.tokenEnv)" -ErrorAction SilentlyContinue }
    }
    # Each daily replica's log, kept before the stack stops.
    foreach ($id in @($daily)) { if ($id) { & docker logs --timestamps $id 2>&1 | ForEach-Object { "$_" } | Set-Content -LiteralPath (Join-Path $logs "scheduler-daily-$id.log") -Encoding utf8 } }
    # A stack that never became healthy has no replica list yet: keep every scheduler service's state and log instead.
    if (@($daily | Where-Object { $_ }).Count -eq 0) {
        & docker @($compose + @('ps', '-a')) *> (Join-Path $logs 'unhealthy-ps.log')
        & docker @($compose + @('logs', '--no-color', '--timestamps', 'scheduler-daily', 'scheduler-booking', 'scheduler-proxy')) *> (Join-Path $logs 'unhealthy-stack.log')
    }
    & docker @($compose + @('down')) *> (Join-Path $logs 'down.log')
    foreach ($key in @($saved.Keys)) { if ($null -eq $saved[$key]) { Remove-Item -Path "env:$key" -ErrorAction SilentlyContinue } else { Set-Item -Path "env:$key" -Value $saved[$key] } }
    [Backtest.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
    $ErrorActionPreference = 'Stop'
}
Write-Output "$(Get-Date -Format s) done after $([math]::Round(((Get-Date) - $started).TotalMinutes)) minutes. Logs: $logs. The stack is down; bring the rehearsal back with its own image as deploy/vm/README.md says."
