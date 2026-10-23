# Load balance head to head (PR J): Timefold's load balance against production's variance (option b), at two budgets,
# on an idle workstation (infra/load_balance_study.py describes the arms and the switching rule).
#   b             TABU_KOPT                 production: the capacity-weighted variance of utilization
#   load-balance  TABU_KOPT_LOAD_BALANCE    Timefold's load balance over utilization, unweighted
# One tree, a detached worktree of -Revision under -TreeRoot (outside OneDrive), built once: main 52339c8 plus the
# MIXED_SHIFT fixture (4, 6 and 9 hour days), a test-only change. A case is one fleet, workload, fixture seed and solver
# seed; the fixture seeds draw different days of the same shape, so the cases are different problems. Every case runs
# as its own JVM with both arms in it, their order alternating from case to case; both budgets run in every repetition,
# so a slow drift in the machine shows in both arms and both budgets. A JFR pass at the first budget follows, then the
# evidence.
# Run output goes to experiments/runs/<run-id>/ of the main checkout, whichever worktree this script runs from.
# Long (about 3 hours 35 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-load-balance-study.ps1
# A check of about 15 minutes that writes no evidence: add -Smoke.
param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$Revision = '15da8a0f0ca5802c817849f07c29b6f03921130f',
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees\load-balance-h2h-tree',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Comma-separated, as -File passes every value as text. 15000 is the production default (10 s cost, 5 s fairness).
    [string]$Budgets = '15000,30000',
    [string]$Sizes = '5,10,20,50',
    [string]$Workloads = 'CLUSTERED,DISPERSED,ABSENCE,TIGHT_WINDOW,NEAR_CAPACITY,MIXED_SHIFT',
    # Fixture seeds give different problems; solver seeds give different searches of the same problem.
    [string]$FixtureSeeds = '7001,7002,7003,7004,7005',
    [string]$Seeds = '17',
    [ValidateRange(1, 5)][int]$Repetitions = 1,
    # The JFR pass: every size and workload at these fixture seeds and the first budget, one recording per phase per solve.
    [string]$ProfileFixtureSeeds = '7001',
    [switch]$Smoke,
    # Compose containers compete for the CPU; the run refuses while any are up unless this is given.
    [switch]$AllowContainers
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell turns a native program's stderr into a terminating error under 'Stop'; Maven and the JVM write
# to it as they work, so a native call runs under 'Continue' and is judged by its exit code alone.
function Invoke-Native($program, [string[]]$arguments, $log) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $program @arguments *> $log } finally { $ErrorActionPreference = $previous }
    return $LASTEXITCODE
}

if ($Smoke) { $Budgets = '1500,3000'; $Sizes = '5,20'; $Workloads = 'CLUSTERED,MIXED_SHIFT'; $FixtureSeeds = '7001,7002'; $Seeds = '17'; $Repetitions = 2; $ProfileFixtureSeeds = '7001' }
$repository = Split-Path $PSScriptRoot -Parent
# The main checkout holds every worktree's run output (experiments/runs is gitignored there).
$common = (& git -C $repository rev-parse --path-format=absolute --git-common-dir).Trim()
if ($LASTEXITCODE -ne 0) { throw "Not a git checkout: $repository" }
$main = Split-Path $common -Parent
$java = Join-Path $JavaHome 'bin\java.exe'
$jfr = Join-Path $JavaHome 'bin\jfr.exe'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $jfr)) { throw "No JDK at $JavaHome (java.exe and jfr.exe are needed)" }
$env:JAVA_HOME = $JavaHome
$ErrorActionPreference = 'Continue'
$version = (& $java -version 2>&1 | Select-Object -First 1).ToString()
$running = @()
if (-not $AllowContainers -and (Get-Command docker -ErrorAction SilentlyContinue)) { $running = @(& docker ps -q 2>$null | Where-Object { $_ }) }
$ErrorActionPreference = 'Stop'
if ($version -notmatch '"25') { throw "Java 25 is required, found: $version" }
if ($running.Count -gt 0) { throw "$($running.Count) containers are running; stop them (docker compose stop) or pass -AllowContainers" }
$budgetList = @($Budgets -split ',' | ForEach-Object { [int]$_.Trim() })
if (@($budgetList | Where-Object { $_ -lt 1000 -or $_ -gt 240000 }).Count -gt 0) { throw '-Budgets are milliseconds from 1000 to 240000' }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$runs = Join-Path $main "experiments/runs/load-balance-h2h-$(if ($Smoke) { 'smoke-' })$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows', 'profiles')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Study -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Study.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# Freeze the tree as a detached worktree of the revision and build it once; a tree already built from it is reused.
$built = Join-Path $TreeRoot 'scheduler-service\target\study-built.txt'
if ((Test-Path -LiteralPath $built) -and ((Get-Content -LiteralPath $built -Raw).Trim() -eq $Revision)) {
    Write-Output "reusing the build of $Revision in $TreeRoot"
} else {
    if (Test-Path -LiteralPath $TreeRoot) { throw "$TreeRoot exists and is not a build of $Revision; remove it (git worktree remove) or choose another -TreeRoot" }
    $code = Invoke-Native git @('-C', $main, 'worktree', 'add', '--detach', $TreeRoot, $Revision) (Join-Path $runs 'logs/worktree.log')
    if ($code -ne 0) { throw "git worktree add failed for $Revision (see logs/worktree.log)" }
    Write-Output "building $Revision in $TreeRoot"
    $code = Invoke-Native (Join-Path $TreeRoot 'mvnw.cmd') @('-q', '-f', (Join-Path $TreeRoot 'pom.xml'), '-pl', 'scheduler-service', '-am', '-DskipTests',
        'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/study-classpath.txt', '-DexcludeGroupIds=dev.waterflex') (Join-Path $runs 'logs/build.log')
    if ($code -ne 0) { throw 'The build failed (see logs/build.log)' }
    Set-Content -LiteralPath $built -Value $Revision -Encoding ascii
}
$head = (& git -C $TreeRoot rev-parse HEAD).Trim()
if ($head -ne $Revision) { throw "$TreeRoot is at $head, not $Revision" }
$dependencies = (Get-Content -LiteralPath (Join-Path $TreeRoot 'scheduler-service/target/study-classpath.txt') -Raw).Trim()
# The engine must come from this tree's own classes, never a jar installed in the local Maven repository.
if ($dependencies -match 'calculation-engine') { throw "The tree resolved calculation-engine as a jar: $dependencies" }
$own = @('scheduler-service\target\test-classes', 'scheduler-service\target\classes', 'calculation-engine\target\classes') | ForEach-Object { Join-Path $TreeRoot $_ }
# An argument file keeps the long classpath off the command line.
$classpath = Join-Path $runs 'classpath.args'
Set-Content -LiteralPath $classpath -Value ('-cp "' + (($own + $dependencies) -join ';').Replace('\', '\\') + '"') -Encoding ascii

$jvm = @('-Xmx1536m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC')
$variants = @('TABU_KOPT', 'TABU_KOPT_LOAD_BALANCE')
$sizeList = @($Sizes -split ',' | ForEach-Object { $_.Trim() })
$workloadList = @($Workloads -split ',' | ForEach-Object { $_.Trim() })
$fixtureList = @($FixtureSeeds -split ',' | ForEach-Object { $_.Trim() })
$seedList = @($Seeds -split ',' | ForEach-Object { $_.Trim() })
$profileFixtureList = @($ProfileFixtureSeeds -split ',' | ForEach-Object { $_.Trim() })
$cases = @(foreach ($size in $sizeList) { foreach ($workload in $workloadList) { foreach ($fixture in $fixtureList) { foreach ($seed in $seedList) {
    , @($size, $workload, $fixture, $seed) } } } })
# Each solve takes its budget and about 2.5 s more (JVM start shared, fixture, warmup, validation).
$seconds = 0
foreach ($budget in $budgetList) { $seconds += $cases.Count * $Repetitions * $variants.Count * ($budget / 1000 + 2.5) }
$seconds += $sizeList.Count * $workloadList.Count * $profileFixtureList.Count * $seedList.Count * $variants.Count * ($budgetList[0] / 1000 + 2.5)
@{ study = 'load balance head to head'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke; revision = $Revision
   tree = $TreeRoot; variants = $variants; jvm = $jvm; java = $version; processors = [Environment]::ProcessorCount; budgetsMs = $budgetList
   sizes = $sizeList; workloads = $workloadList; fixtureSeeds = $fixtureList; seeds = $seedList; repetitions = $Repetitions
   profileFixtureSeeds = $profileFixtureList; profileBudgetMs = $budgetList[0]
   order = 'per repetition: every budget, then every case; per case: arm order alternates' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runs 'manifest.json') -Encoding utf8
Write-Output "$($cases.Count) cases x $Repetitions repetitions x $($variants.Count) arms at $($budgetList -join ' and ') ms, about $([math]::Ceiling($seconds / 60)) minutes. Output: $runs"

function Invoke-Benchmark($output, $log, $budget, $properties) {
    $arguments = $jvm + @("@$classpath") + $properties + @("-Dbenchmark.output=$output", "-Dbenchmark.durationMs=$budget",
        "-Dbenchmark.revision=load-balance-study revision=$Revision",
        'dev.waterflex.scheduler.optimizer.SolverBenchmark')
    return Invoke-Native $java $arguments $log
}

$failures = @()
$index = 0
$total = $Repetitions * $budgetList.Count * $cases.Count
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    foreach ($budget in $budgetList) {
        foreach ($case in $cases) {
            $index++
            $size, $workload, $fixture, $seed = $case
            $order = if (($index + $repetition) % 2 -eq 0) { $variants } else { @($variants[1], $variants[0]) }
            $name = "rep$repetition-$($budget)ms-$size-$workload-$fixture-$seed"
            Write-Output "[$index/$total] $name"
            $code = Invoke-Benchmark (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") $budget `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.fixtureSeeds=$fixture", "-Dbenchmark.seeds=$seed",
                  "-Dbenchmark.variants=$($order -join ',')")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
Write-Output '[profile]'
$code = Invoke-Benchmark (Join-Path $runs 'profile-rows/profile.jsonl') (Join-Path $runs 'logs/profile.log') $budgetList[0] `
    @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.fixtureSeeds=$ProfileFixtureSeeds", "-Dbenchmark.seeds=$Seeds",
      "-Dbenchmark.variants=$($variants -join ',')",
      "-Dbenchmark.jfr=$(Join-Path $runs 'profiles')")
if ($code -ne 0) { $failures += 'profile' }
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = Join-Path $runs $(if ($Smoke) { 'smoke-summary.json' } else { 'evidence.json' })
& python (Join-Path $repository 'infra/load_balance_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
Write-Output "The tree stays in $TreeRoot for a rerun; remove it with: git -C $main worktree remove $TreeRoot"
