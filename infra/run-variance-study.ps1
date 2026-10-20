# Fairness measure study (PR 187): two variance formulas and four fairness policies against production, at each
# budget, on an idle workstation (infra/variance_study.py describes the arms and the bars).
#   baseline      TABU_KOPT                       production: DECIMAL128 variance (nanosecond timing, as #186)
#   b             TABU_KOPT_BINARY_VARIANCE       the same variance in binary64 as rounded units of 1e-9
#   c             TABU_KOPT_INCREMENTAL_VARIANCE  the same variance from exact running sums
#   min-max       TABU_KOPT_MIN_MAX_UTILIZATION   the highest technician utilization
#   spread        TABU_KOPT_UTILIZATION_SPREAD    the highest minus the lowest utilization
#   abs-dev       TABU_KOPT_ABSOLUTE_DEVIATION    the capacity-weighted mean absolute deviation of utilization
#   load-balance  TABU_KOPT_LOAD_BALANCE          Timefold's load balance over utilization
# One tree, frozen from its commit into -BuildRoot and built once. Every case runs as its own JVM with all seven
# variants in it, their order rotating from case to case. A JFR pass at the first budget follows, then the evidence.
# Long (about 7 hours 30 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-variance-study.ps1
# A six-minute harness check that writes no evidence: add -Smoke.
param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$Revision = 'fd3e1911abdc79685e9a01f79f6b2b8fc1949506',
    [string]$BuildRoot = 'C:\wffm',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # Comma-separated, as -File passes every value as text. 15000 is the production default (10 s cost, 5 s fairness).
    [string]$Budgets = '15000,60000',
    [string]$Sizes = '20,50',
    [string]$Workloads = 'CLUSTERED,DISPERSED,ABSENCE,TIGHT_WINDOW',
    [string]$Seeds = '17,23,41',
    [ValidateRange(0, 1000000)][int]$FixtureSeed = 7001,
    [ValidateRange(1, 5)][int]$Repetitions = 2,
    [string]$ProfileSeeds = '17',
    [switch]$Smoke,
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

if ($Smoke) { $Budgets = '1500'; $Sizes = '20'; $Workloads = 'CLUSTERED'; $Seeds = '17'; $Repetitions = 2; $ProfileSeeds = '17' }
$repository = Split-Path $PSScriptRoot -Parent
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
$evidence = if ($Smoke) { $null } else { Join-Path $repository "docs/evidence/solver-variance-$([DateTime]::UtcNow.ToString('yyyy-MM-dd')).json" }
if ($evidence -and (Test-Path -LiteralPath $evidence)) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository ".scratch/variance-study-$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows', 'profiles')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Study -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Study.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

$trees = [ordered]@{ v = $Revision }
$classpaths = @{}
foreach ($tree in $trees.Keys) {
    $revision = $trees[$tree]
    $directory = Join-Path $BuildRoot $tree
    $marker = Join-Path $directory '.study-built'
    if ((Test-Path -LiteralPath $marker) -and ((Get-Content -LiteralPath $marker -Raw).Trim() -eq $revision)) {
        Write-Output "[$tree] reusing the build of $revision in $directory"
    } else {
        if (Test-Path -LiteralPath $directory) { throw "$directory exists and is not a build of $revision; remove it or choose another -BuildRoot" }
        New-Item -ItemType Directory -Force $directory | Out-Null
        $archive = Join-Path $BuildRoot "$tree.tar"
        & git -C $repository archive --format=tar -o $archive $revision
        if ($LASTEXITCODE -ne 0) { throw "git archive failed for $revision" }
        & tar -xf $archive -C $directory
        if ($LASTEXITCODE -ne 0) { throw "Could not unpack $archive" }
        Remove-Item -LiteralPath $archive
        Write-Output "[$tree] building $revision in $directory"
        $code = Invoke-Native (Join-Path $directory 'mvnw.cmd') @('-q', '-f', (Join-Path $directory 'pom.xml'), '-pl', 'scheduler-service', '-am', '-DskipTests',
            'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=study-classpath.txt', '-DexcludeGroupIds=dev.waterflex') (Join-Path $runs "logs/build-$tree.log")
        if ($code -ne 0) { throw "Build of tree $tree failed (see logs/build-$tree.log)" }
        Set-Content -LiteralPath $marker -Value $revision -Encoding ascii
    }
    $dependencies = (Get-Content -LiteralPath (Join-Path $directory 'scheduler-service/study-classpath.txt') -Raw).Trim()
    # The engine must come from this tree's own classes, never a jar installed in the local Maven repository.
    if ($dependencies -match 'calculation-engine') { throw "Tree $tree resolved calculation-engine as a jar: $dependencies" }
    $own = @('scheduler-service\target\test-classes', 'scheduler-service\target\classes', 'calculation-engine\target\classes') | ForEach-Object { Join-Path $directory $_ }
    # An argument file keeps the long classpath off the command line.
    $arguments = Join-Path $runs "classpath-$tree.args"
    Set-Content -LiteralPath $arguments -Value ('-cp "' + (($own + $dependencies) -join ';').Replace('\', '\\') + '"') -Encoding ascii
    $classpaths[$tree] = $arguments
}

$jvm = @('-Xmx1536m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC')
$variants = @('TABU_KOPT', 'TABU_KOPT_BINARY_VARIANCE', 'TABU_KOPT_INCREMENTAL_VARIANCE', 'TABU_KOPT_MIN_MAX_UTILIZATION',
    'TABU_KOPT_UTILIZATION_SPREAD', 'TABU_KOPT_ABSOLUTE_DEVIATION', 'TABU_KOPT_LOAD_BALANCE')
$arms = $variants.Count
$sizeList = @($Sizes -split ',' | ForEach-Object { $_.Trim() })
$workloadList = @($Workloads -split ',' | ForEach-Object { $_.Trim() })
$seedList = @($Seeds -split ',' | ForEach-Object { $_.Trim() })
$cases = @(foreach ($size in $sizeList) { foreach ($workload in $workloadList) { foreach ($seed in $seedList) { , @($size, $workload, $seed) } } })
$seconds = 0
foreach ($budget in $budgetList) { $seconds += $cases.Count * $Repetitions * ($arms * $budget / 1000 + 8) }
$seconds += $sizeList.Count * $workloadList.Count * @($ProfileSeeds -split ',').Count * ($arms * $budgetList[0] / 1000 + 3)
@{ study = 'fairness measures'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke; revision = $Revision
   variants = $variants; jvm = $jvm; java = $version; processors = [Environment]::ProcessorCount; budgetsMs = $budgetList
   sizes = $sizeList; workloads = $workloadList; seeds = $seedList; fixtureSeed = $FixtureSeed; repetitions = $Repetitions
   profileSeeds = @($ProfileSeeds -split ','); order = 'per case: variant order rotates' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runs 'manifest.json') -Encoding utf8
Write-Output "$($cases.Count) cases x $Repetitions repetitions x $arms arms at $($budgetList -join ' and ') ms, about $([math]::Ceiling($seconds / 60)) minutes. Output: $runs"

function Invoke-Benchmark($output, $log, $budget, $properties) {
    $arguments = $jvm + @("@$($classpaths['v'])") + $properties + @("-Dbenchmark.output=$output", "-Dbenchmark.durationMs=$budget",
        "-Dbenchmark.fixtureSeeds=$FixtureSeed", "-Dbenchmark.revision=variance-study revision=$Revision",
        'dev.waterflex.scheduler.optimizer.SolverBenchmark')
    return Invoke-Native $java $arguments $log
}

$failures = @()
$index = 0
$total = $budgetList.Count * $Repetitions * $cases.Count
foreach ($budget in $budgetList) {
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        foreach ($case in $cases) {
            $index++
            $size, $workload, $seed = $case
            $order = @(for ($offset = 0; $offset -lt $arms; $offset++) { $variants[(($index + $offset) % $arms)] })
            $name = "rep$repetition-$($budget)ms-$size-$workload-$seed"
            Write-Output "[$index/$total] $name"
            $code = Invoke-Benchmark (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") $budget `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.seeds=$seed", "-Dbenchmark.variants=$($order -join ',')")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
Write-Output '[profile]'
$code = Invoke-Benchmark (Join-Path $runs 'profile-rows/profile.jsonl') (Join-Path $runs 'logs/profile.log') $budgetList[0] `
    @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.seeds=$ProfileSeeds", "-Dbenchmark.variants=$($variants -join ',')",
      "-Dbenchmark.jfr=$(Join-Path $runs 'profiles')")
if ($code -ne 0) { $failures += 'profile' }
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = if ($Smoke) { Join-Path $runs 'smoke-summary.json' } else { $evidence }
& python (Join-Path $repository 'infra/variance_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
