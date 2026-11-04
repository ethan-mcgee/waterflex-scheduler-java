# Overnight budget convergence check (infra/backtest_convergence.py): 40 calibration days weighted toward the busiest,
# each first given a start that holds (a short repair solve of the dispatcher's booked plan), then solved from that
# start by the production daily calculation under each arm (TABU_KOPT_SIZE_3 at 15, 30 and 60 s, a second 15 s seed
# for noise, TABU_KOPT at 15 s), one JVM per day and arm, arms in a rotating order per day.
# Steps: build the frozen tree of -Revision once (outside OneDrive), screen every calibration day in one JVM, draw the
# candidates once (never redrawn), solve the starts, write the selection, run the arms, summarize. Every step resumes.
# Output: <RunDir>/convergence/{screen.json, candidates.json, seed/, starts/, selection.json, rows/<day>/<arm>/};
# the summary goes to -ReportDir.
#   powershell -ExecutionPolicy Bypass -File infra/run-backtest-convergence.ps1 -RunDir experiments/runs/<run-id>/variants/strict-17 -Revision <sha> -ReportDir research/<study>/convergence [-DryRun]
param(
    [Parameter(Mandatory)][string]$RunDir,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision,
    [Parameter(Mandatory)][string]$ReportDir,
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees\backtest-tree',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Compose containers compete for the CPU; the run refuses while any are up unless this is given.
    [switch]$AllowContainers,
    # Checks the inputs and prints the plan and its time without building, screening or solving.
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
function Write-Json($value, $path, $depth) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth $depth), (New-Object Text.UTF8Encoding $false))
}
function Invoke-Native($program, [string[]]$arguments, $log) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $program @arguments *> $log } finally { $ErrorActionPreference = $previous }
    return $LASTEXITCODE
}
function Invoke-Python([string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & python @arguments 2>&1 } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "python $($arguments -join ' ') failed: $output" }
    return ($output -join "`n")
}

$repository = Split-Path $PSScriptRoot -Parent
$common = (& git -C $repository rev-parse --path-format=absolute --git-common-dir).Trim()
if ($LASTEXITCODE -ne 0) { throw "Not a git checkout: $repository" }
$main = Split-Path $common -Parent
$run = if ([IO.Path]::IsPathRooted($RunDir)) { $RunDir } else { Join-Path $main $RunDir }
$report = if ([IO.Path]::IsPathRooted($ReportDir)) { $ReportDir } else { Join-Path $main $ReportDir }
if (-not (Test-Path -LiteralPath (Join-Path $run 'index.json'))) { throw "No importer index at $run" }
$index = Get-Content -LiteralPath (Join-Path $run 'index.json') -Raw | ConvertFrom-Json
$config = Get-Content -LiteralPath (Join-Path $run 'study-config.json') -Raw | ConvertFrom-Json
if ($index.routingIdentity -like 'stub-*') { throw "The run at $run was imported with the stub road model" }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "No JDK at $JavaHome" }
$env:JAVA_HOME = $JavaHome
$ErrorActionPreference = 'Continue'
$version = (& $java -version 2>&1 | Select-Object -First 1).ToString()
$running = @()
if (-not $AllowContainers -and (Get-Command docker -ErrorAction SilentlyContinue)) { $running = @(& docker ps -q 2>$null | Where-Object { $_ }) }
$ErrorActionPreference = 'Stop'
if ($version -notmatch '"25') { throw "Java 25 is required, found: $version" }
if ($running.Count -gt 0 -and -not $DryRun) { throw "$($running.Count) containers are running; stop them (docker compose stop) or pass -AllowContainers" }

$tool = Join-Path $repository 'infra/backtest_convergence.py'
$conv = Join-Path $run 'convergence'
$screen = Join-Path $conv 'screen.json'
$candidates = Join-Path $conv 'candidates.json'
$selection = Join-Path $conv 'selection.json'
$arms = Invoke-Python @($tool, 'arms') | ConvertFrom-Json
$startArm = Invoke-Python @($tool, 'start-arm') | ConvertFrom-Json
$perDay = 0
foreach ($arm in $arms) { $perDay += ($arm.costMs + $arm.fairnessMs) / 1000 + 3 }
$calibration = @($index.days | Where-Object { $_.split -eq 'calibration' }).Count
Write-Output "Arms: $(($arms | ForEach-Object { $_.name }) -join ', '); about $([math]::Round($perDay)) s per day"
$startSeconds = ($startArm.costMs + $startArm.fairnessMs) / 1000 + 3
Write-Output "Screen: $calibration calibration days in one JVM (seconds); starts: about $([math]::Ceiling(48 * $startSeconds / 60)) minutes for about 48 candidates"
Write-Output "Solving: about $([math]::Ceiling(40 * $perDay / 60)) minutes for 40 days, plus the build when the tree is not at $Revision"
if ($DryRun) {
    if (Test-Path -LiteralPath $selection) { Write-Output "Selection already drawn: $((Get-Content -LiteralPath $selection -Raw | ConvertFrom-Json).count) days" }
    return
}

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Convergence -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Convergence.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# The frozen tree, as infra/run-backtest-study.ps1 builds it; a tree already built from the revision is reused.
$jar = Join-Path $TreeRoot 'campaign-benchmark\target\campaign-benchmark-0.1.0-SNAPSHOT.jar'
$built = Join-Path $TreeRoot 'campaign-benchmark\target\backtest-built.txt'
$logs = Join-Path $conv 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
if ((Test-Path -LiteralPath $built) -and ((Get-Content -LiteralPath $built -Raw).Trim() -eq $Revision) -and (Test-Path -LiteralPath $jar)) {
    Write-Output "reusing the build of $Revision in $TreeRoot"
} else {
    if (Test-Path -LiteralPath $TreeRoot) { throw "$TreeRoot exists and is not a build of $Revision; remove it (git worktree remove) or choose another -TreeRoot" }
    $code = Invoke-Native git @('-C', $main, 'worktree', 'add', '--detach', $TreeRoot, $Revision) (Join-Path $logs 'worktree.log')
    if ($code -ne 0) { throw "git worktree add failed for $Revision (see logs/worktree.log)" }
    Write-Output "building $Revision in $TreeRoot"
    $code = Invoke-Native (Join-Path $TreeRoot 'mvnw.cmd') @('-q', '-f', (Join-Path $TreeRoot 'pom.xml'), '-Pcampaign-benchmark', '-pl', 'campaign-benchmark', '-am', '-DskipTests', 'package') (Join-Path $logs 'build.log')
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw 'The build failed (see logs/build.log)' }
    Set-Content -LiteralPath $built -Value $Revision -Encoding ascii
}
$head = (& git -C $TreeRoot rev-parse HEAD).Trim()
if ($head -ne $Revision) { throw "$TreeRoot is at $head, not $Revision" }
$jvm = @('-Xmx1536m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC')

# Screen once: every calibration day's booked plan read in one JVM.
if (-not (Test-Path -LiteralPath $screen)) {
    $job = Join-Path $conv 'screen-job.json'
    Invoke-Python @($tool, 'screen-job', '--run', $run, '--split', 'calibration', '--screen', $screen, '--out', $job) | Out-Null
    Write-Output "$(Get-Date -Format s) screening $calibration calibration days"
    $code = Invoke-Native $java ($jvm + @('-jar', $jar, $job, (Join-Path $conv 'screen-receipt.json'))) (Join-Path $logs 'screen.log')
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $screen)) { throw 'The screen failed (see convergence/logs/screen.log)' }
}
# Draw once: the candidates are never redrawn, so a resumed run solves the same days.
if (-not (Test-Path -LiteralPath $candidates)) { Invoke-Python @($tool, 'candidates', '--screen', $screen, '--out', $candidates) | Out-Null }
$policy = [ordered]@{ regularWindowThreshold = [int]$config.policy.regularWindowThreshold; fairnessAllowance = [string]$config.policy.fairnessBudget; bookingDeadlineMs = [int]$config.policy.bookingDeadlineMs }

# The starts: each candidate's booked plan through a short repair solve, in the candidates' order, until every tercile
# has its quota of starts that hold. next-seed names the next candidate and turns each finished solve into its start.
if (-not (Test-Path -LiteralPath $selection)) {
    while ($true) {
        $day = (Invoke-Python @($tool, 'next-seed', '--run', $run, '--conv', $conv)).Trim()
        if ($day -eq 'DONE') { break }
        if ($day -notmatch '^\d{4}-\d{2}-\d{2}$') { throw "next-seed answered '$day'" }
        $output = Join-Path $conv "seed\$day"
        New-Item -ItemType Directory -Force $output | Out-Null
        Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -notlike '*.sealed.json' } | Remove-Item -Force
        $job = [ordered]@{ version = 1; kind = 'backtest-day'; directory = (Join-Path $run "days\$day"); output = $output
            variant = $startArm.variant; seed = [long]$startArm.seed; costMs = [long]$startArm.costMs; fairnessMs = [long]$startArm.fairnessMs; policy = $policy }
        $request = Join-Path $output 'job.json'
        Write-Json $job $request 4
        Write-Output "start $day"
        $code = Invoke-Native $java ($jvm + @('-jar', $jar, $request, (Join-Path $output 'receipt.json'))) (Join-Path $output 'log.txt')
        if ($code -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $output 'receipt.json'))) { throw "The start solve of $day failed (see $output\log.txt)" }
    }
    Write-Output (Invoke-Python @($tool, 'plan', '--run', $run, '--conv', $conv, '--out', $selection))
}
$chosen = Get-Content -LiteralPath $selection -Raw | ConvertFrom-Json
if ($chosen.count -lt 1) { throw 'No day has a usable start' }
$byName = @{}
foreach ($arm in $arms) { $byName[$arm.name] = $arm }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$manifest = [ordered]@{ study = 'overnight budget convergence'; startedAt = [DateTime]::UtcNow.ToString('o'); revision = $Revision; tree = $TreeRoot
    java = $version; jvm = $jvm; processors = [Environment]::ProcessorCount; arms = $arms; start = $startArm; days = $chosen.count; sources = $chosen.sources
    routingIdentity = $index.routingIdentity; policy = $policy }
Write-Json $manifest (Join-Path $conv "manifest-$stamp.json") 6
$total = $chosen.count * $arms.Count
Write-Output "$($chosen.count) days x $($arms.Count) arms, about $([math]::Ceiling($chosen.count * $perDay / 60)) minutes. Output: $conv\rows"

$failures = @()
$skipped = 0
$count = 0
$started = Get-Date
foreach ($entry in $chosen.plan) {
    foreach ($name in $entry.arms) {
        $count++
        $arm = $byName[$name]
        if ($null -eq $arm) { throw "The selection names an unknown arm $name" }
        $output = Join-Path $conv "rows\$($entry.day)\$name"
        $receipt = Join-Path $output 'receipt.json'
        if (Test-Path -LiteralPath $receipt) { $skipped++; continue }
        New-Item -ItemType Directory -Force $output | Out-Null
        Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -notlike '*.sealed.json' } | Remove-Item -Force
        $job = [ordered]@{ version = 1; kind = 'backtest-day'; directory = $entry.directory; output = $output
            variant = $arm.variant; seed = [long]$arm.seed; costMs = [long]$arm.costMs; fairnessMs = [long]$arm.fairnessMs; policy = $policy }
        $request = Join-Path $output 'job.json'
        Write-Json $job $request 4
        Write-Output "[$count/$total] $($entry.day) $name"
        $code = Invoke-Native $java ($jvm + @('-jar', $jar, $request, $receipt)) (Join-Path $output 'log.txt')
        if ($code -ne 0 -or -not (Test-Path -LiteralPath $receipt)) { $failures += "$($entry.day) $name" }
    }
}
[Convergence.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
Write-Output "Ran $($total - $skipped) cases in $([math]::Round(((Get-Date) - $started).TotalMinutes)) minutes, skipped $skipped already finished"
if ($failures.Count -gt 0) { throw "Cases failed (see their log.txt): $($failures -join ', ')" }
Write-Output (Invoke-Python @($tool, 'summarize', '--conv', $conv, '--selection', $selection, '--out', $report))
Write-Output "Report: $report\report.md. The tree stays in $TreeRoot; remove it with: git -C $main worktree remove $TreeRoot"
