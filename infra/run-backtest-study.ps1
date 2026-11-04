# Backtest harness run: every day of an imported work-order history (infra/backtest_import.py) solved by the production
# daily calculation and read beside the dispatcher's plans, one JVM per day (campaign-benchmark's backtest-day job).
# The tree is a detached worktree of -Revision under -TreeRoot (outside OneDrive), built once. Each day's receipt
# resumes a stopped run; a day whose receipt exists for the same seed and repetition is skipped.
# Output goes to <RunDir>/rows/<day>/seed<seed>-rep<n>/ beside the importer's days; the summary reads it from there.
# Long (about 3.5 hours for 740 days at the production budget); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-backtest-study.ps1 -RunDir experiments/runs/<run-id> -Revision <sha>
# A check of a few minutes on the first 10 days at short budgets: add -Smoke.
# The noise pass solves a stratified sample again with more seeds: -Days noise:50 -Seeds 17,18,19,20,21
param(
    [Parameter(Mandatory)][string]$RunDir,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$Revision,
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees\backtest-tree',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Which days: all, calibration, test, noise:<n> (a stratified sample of the test split), or a comma-separated list of dates.
    [string]$Days = 'all',
    # Comma-separated solver seeds; the primary run uses one, the noise pass several.
    [string]$Seeds = '17',
    [ValidateRange(1, 10)][int]$Repetitions = 1,
    [ValidateSet('TABU_KOPT', 'TABU', 'TABU_KOPT_SIZE_3')][string]$Variant = 'TABU_KOPT',
    # The production allocation: 10 s cost search, 5 s fairness search.
    [ValidateRange(1, 240000)][int]$CostMs = 10000,
    [ValidateRange(0, 240000)][int]$FairnessMs = 5000,
    [switch]$Smoke,
    # Compose containers compete for the CPU; the run refuses while any are up unless this is given.
    [switch]$AllowContainers
)
$ErrorActionPreference = 'Stop'
function Invoke-Native($program, [string[]]$arguments, $log) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $program @arguments *> $log } finally { $ErrorActionPreference = $previous }
    return $LASTEXITCODE
}

$repository = Split-Path $PSScriptRoot -Parent
$common = (& git -C $repository rev-parse --path-format=absolute --git-common-dir).Trim()
if ($LASTEXITCODE -ne 0) { throw "Not a git checkout: $repository" }
$main = Split-Path $common -Parent
$run = if ([IO.Path]::IsPathRooted($RunDir)) { $RunDir } else { Join-Path $main $RunDir }
if (-not (Test-Path -LiteralPath (Join-Path $run 'index.json'))) { throw "No importer index at $run (run infra/backtest_import.py build first)" }
$index = Get-Content -LiteralPath (Join-Path $run 'index.json') -Raw | ConvertFrom-Json
$config = Get-Content -LiteralPath (Join-Path $run 'study-config.json') -Raw | ConvertFrom-Json
if ($index.routingIdentity -like 'stub-*') { throw "The run at $run was imported with the stub road model; import it against the routing service first" }
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "No JDK at $JavaHome" }
$env:JAVA_HOME = $JavaHome
$ErrorActionPreference = 'Continue'
$version = (& $java -version 2>&1 | Select-Object -First 1).ToString()
$running = @()
if (-not $AllowContainers -and (Get-Command docker -ErrorAction SilentlyContinue)) { $running = @(& docker ps -q 2>$null | Where-Object { $_ }) }
$ErrorActionPreference = 'Stop'
if ($version -notmatch '"25') { throw "Java 25 is required, found: $version" }
if ($running.Count -gt 0) { throw "$($running.Count) containers are running; stop them (docker compose stop) or pass -AllowContainers" }
if ($Smoke) { $CostMs = 1500; $FairnessMs = 700; if ($Days -eq 'all') { $Days = 'smoke' } }

# The days, in date order, chosen by the summary's selector so the noise sample is the same one the report pairs.
$selector = @('python', (Join-Path $repository 'infra/backtest_summary.py'), 'days', '--run', $run, '--select', $Days)
$ErrorActionPreference = 'Continue'
$selected = & $selector[0] $selector[1..($selector.Count - 1)] 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($code -ne 0) { throw "Day selection failed: $selected" }
$dayList = @(($selected -join "`n") -split "`r?`n" | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2}$' })
if ($dayList.Count -eq 0) { throw "No days selected by '$Days'" }
$seedList = @($Seeds -split ',' | ForEach-Object { [long]$_.Trim() })

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Backtest -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Backtest.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# Freeze the tree as a detached worktree of the revision and build the benchmark jar once; a tree already built is reused.
$jar = Join-Path $TreeRoot 'campaign-benchmark\target\campaign-benchmark-0.1.0-SNAPSHOT.jar'
$built = Join-Path $TreeRoot 'campaign-benchmark\target\backtest-built.txt'
$logs = Join-Path $run 'logs'
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
$policy = [ordered]@{ regularWindowThreshold = [int]$config.policy.regularWindowThreshold; fairnessAllowance = [string]$config.policy.fairnessBudget; bookingDeadlineMs = [int]$config.policy.bookingDeadlineMs }
$total = $dayList.Count * $seedList.Count * $Repetitions
$seconds = $total * (($CostMs + $FairnessMs) / 1000 + 3)
$manifest = [ordered]@{ study = 'backtest harness'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke; revision = $Revision; tree = $TreeRoot
    java = $version; jvm = $jvm; processors = [Environment]::ProcessorCount; variant = $Variant; costMs = $CostMs; fairnessMs = $FairnessMs
    days = $Days; dayCount = $dayList.Count; seeds = $seedList; repetitions = $Repetitions; routingIdentity = $index.routingIdentity; policy = $policy }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run "manifest-$stamp.json") -Encoding utf8
Write-Output "$($dayList.Count) days x $($seedList.Count) seeds x $Repetitions repetitions at $CostMs + $FairnessMs ms, about $([math]::Ceiling($seconds / 60)) minutes. Output: $run\rows"

$failures = @()
$skipped = 0
$count = 0
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    foreach ($seed in $seedList) {
        foreach ($day in $dayList) {
            $count++
            $name = "seed$seed-rep$repetition"
            $output = Join-Path $run "rows\$day\$name"
            $receipt = Join-Path $output 'receipt.json'
            if (Test-Path -LiteralPath $receipt) { $skipped++; continue }
            New-Item -ItemType Directory -Force $output | Out-Null
            # A day that stopped half way leaves files the job refuses to overwrite; clear everything but the seals it reuses.
            Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -notlike '*.sealed.json' } | Remove-Item -Force
            $job = [ordered]@{ version = 1; kind = 'backtest-day'; directory = (Join-Path $run "days\$day"); output = $output
                variant = $Variant; seed = $seed; costMs = $CostMs; fairnessMs = $FairnessMs; policy = $policy }
            $request = Join-Path $output 'job.json'
            $job | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $request -Encoding utf8
            Write-Output "[$count/$total] $day $name"
            $code = Invoke-Native $java ($jvm + @('-jar', $jar, $request, $receipt)) (Join-Path $output 'log.txt')
            if ($code -ne 0 -or -not (Test-Path -LiteralPath $receipt)) { $failures += "$day $name" }
        }
    }
}
[Backtest.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
Write-Output "Ran $($total - $skipped) days, skipped $skipped already finished"
if ($failures.Count -gt 0) { throw "Days failed (see their log.txt): $($failures -join ', ')" }
Write-Output "Done. Summarize with: python infra/backtest_summary.py summarize --run $run --out research/backtest-<client>-<date>/"
Write-Output "The tree stays in $TreeRoot for a rerun; remove it with: git -C $main worktree remove $TreeRoot"
