# Part 1 of a backtest (infra/backtest_dispatcher.py): the dispatcher's schedule as it ran, scored and summarized.
# Steps: build the frozen tree of -Revision once (outside OneDrive), write the backtest-score-days job for every day of
# the as-run rule set, score every day's as-planned plan in one JVM, then summarize the rows into the report.
# Scoring times nothing, so running containers are allowed. A rerun scores again and overwrites the rows and the report.
# Output: <RunDir>/part1/{job.json, rows.json, receipt.json, logs/}; the report and its evidence go to -ReportDir.
#   powershell -ExecutionPolicy Bypass -File infra/run-backtest-dispatcher.ps1 -RunDir experiments/runs/<run-id>/variants/as-run -Revision <sha> -ReportDir research/<study>/part1 [-Title <title>] [-DryRun]
param(
    [Parameter(Mandatory)][string]$RunDir,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision,
    [Parameter(Mandatory)][string]$ReportDir,
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees\backtest-tree',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # The report's H1 (infra/backtest_dispatcher.py's own title when empty), for a backtest other than the first.
    [string]$Title = '',
    # Checks the inputs and prints the plan without building, scoring or summarizing.
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
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
if ($index.ruleSet -ne 'as-run') { throw "Part 1 scores the as-run rule set; $run is $($index.ruleSet)" }
if ($index.routingIdentity -like 'stub-*') { throw "The run at $run was imported with the stub road model" }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "No JDK at $JavaHome" }
$env:JAVA_HOME = $JavaHome
$ErrorActionPreference = 'Continue'
$version = (& $java -version 2>&1 | Select-Object -First 1).ToString()
$ErrorActionPreference = 'Stop'
if ($version -notmatch '"25') { throw "Java 25 is required, found: $version" }

$tool = Join-Path $repository 'infra/backtest_dispatcher.py'
$part = Join-Path $run 'part1'
$jobFile = Join-Path $part 'job.json'
$rows = Join-Path $part 'rows.json'
$jobs = ($index.days | Measure-Object -Property jobs -Sum).Sum
Write-Output "Part 1: $($index.days.Count) days, $jobs jobs of $($index.runId), scored in one JVM (a few minutes), plus the build when the tree is not at $Revision"
Write-Output "Rows: $rows; report: $report$(if ($Title) { " ($Title)" })"
if ($DryRun) { return }

# The frozen tree, as infra/run-backtest-convergence.ps1 builds it; a tree already built from the revision is reused.
$jar = Join-Path $TreeRoot 'campaign-benchmark\target\campaign-benchmark-0.1.0-SNAPSHOT.jar'
$built = Join-Path $TreeRoot 'campaign-benchmark\target\backtest-built.txt'
$logs = Join-Path $part 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
if ((Test-Path -LiteralPath $built) -and ((Get-Content -LiteralPath $built -Raw).Trim() -eq $Revision) -and (Test-Path -LiteralPath $jar)) {
    Write-Output "reusing the build of $Revision in $TreeRoot"
} else {
    if (Test-Path -LiteralPath $TreeRoot) { throw "$TreeRoot exists and is not a build of $Revision; remove it (git worktree remove) or choose another -TreeRoot" }
    $code = Invoke-Native git @('-C', $main, 'worktree', 'add', '--detach', $TreeRoot, $Revision) (Join-Path $logs 'worktree.log')
    if ($code -ne 0) { throw "git worktree add failed for $Revision (see part1/logs/worktree.log)" }
    Write-Output "building $Revision in $TreeRoot"
    $code = Invoke-Native (Join-Path $TreeRoot 'mvnw.cmd') @('-q', '-f', (Join-Path $TreeRoot 'pom.xml'), '-Pcampaign-benchmark', '-pl', 'campaign-benchmark', '-am', '-DskipTests', 'package') (Join-Path $logs 'build.log')
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $jar)) { throw 'The build failed (see part1/logs/build.log)' }
    Set-Content -LiteralPath $built -Value $Revision -Encoding ascii
}
$head = (& git -C $TreeRoot rev-parse HEAD).Trim()
if ($head -ne $Revision) { throw "$TreeRoot is at $head, not $Revision" }

$started = Get-Date
Write-Output (Invoke-Python @($tool, 'job', '--run', $run, '--rows', $rows, '--job', $jobFile))
$code = Invoke-Native $java @('-Xmx3g', '-XX:+UseSerialGC', '-jar', $jar, $jobFile, (Join-Path $part 'receipt.json')) (Join-Path $logs 'score.log')
if ($code -ne 0 -or -not (Test-Path -LiteralPath $rows)) { throw 'Scoring failed (see part1/logs/score.log)' }
Write-Output "$(Get-Date -Format s) scored in $([math]::Round(((Get-Date) - $started).TotalMinutes, 1)) minutes: $(Get-Content -LiteralPath (Join-Path $part 'receipt.json') -Raw)"
$summarize = @($tool, 'summarize', '--run', $run, '--rows', $rows, '--out', $report)
if ($Title) { $summarize += @('--title', $Title) }
Write-Output (Invoke-Python $summarize)
Write-Output "Report: $report\report.md. The tree stays in $TreeRoot; remove it with: git -C $main worktree remove $TreeRoot"
