# Road matrix numbering study (PR 187): the fix itself, then b, c and load balance again on it, at one budget, on an idle
# workstation (infra/matrix_study.py describes the arms and the bars).
#   old           tree o, TABU_KOPT                       production before the fix (d571d83, as the fairness measure study ran it)
#   old-b         tree o, TABU_KOPT_BINARY_VARIANCE       option (b) before the fix
#   baseline      tree n, TABU_KOPT                       production with numbered visits and routes (ed97d8b)
#   b             tree n, TABU_KOPT_BINARY_VARIANCE
#   c             tree n, TABU_KOPT_INCREMENTAL_VARIANCE
#   load-balance  tree n, TABU_KOPT_LOAD_BALANCE
# Each tree is frozen from its commit into -BuildRoot and built once. Every case runs as its own JVM per tree, with the
# tree order alternating and the variant order rotating from case to case, so a slow drift in the machine shows in every
# arm. A JFR pass per tree follows, then the evidence file.
# Long (about 2 hours 30 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-matrix-study.ps1
# A four-minute harness check that writes no evidence: add -Smoke.
param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$OldRevision = 'd571d834f4eb9fd829ea9406ff5a948ac929b4b8',
    [ValidatePattern('^[0-9a-f]{40}$')][string]$FixedRevision = 'ed97d8b6c6d4c5956e60d972a8ed99cacd2b596c',
    [string]$BuildRoot = 'C:\wfmx',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # 15000 is the production default (10 s cost, 5 s fairness).
    [ValidateRange(1000, 240000)][int]$BudgetMs = 15000,
    # Comma-separated, as -File passes every value as text.
    [string]$Sizes = '10,30,50',
    [string]$Workloads = 'CLUSTERED,DISPERSED,ABSENCE,TIGHT_WINDOW',
    [string]$Seeds = '17,23,41',
    [ValidateRange(0, 1000000)][int]$FixtureSeed = 7001,
    [ValidateRange(1, 5)][int]$Repetitions = 2,
    # The JFR pass: every size and workload at these seeds, one recording per measured solve.
    [string]$ProfileSeeds = '17',
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

if ($Smoke) { $BudgetMs = 1500; $Sizes = '10'; $Workloads = 'CLUSTERED'; $Seeds = '17'; $Repetitions = 2; $ProfileSeeds = '17' }
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
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$evidence = if ($Smoke) { $null } else { Join-Path $repository "docs/evidence/solver-matrix-$([DateTime]::UtcNow.ToString('yyyy-MM-dd')).json" }
if ($evidence -and (Test-Path -LiteralPath $evidence)) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository ".scratch/matrix-study-$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows', 'profiles/o', 'profiles/n')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Study -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Study.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# Freeze and build each tree once; a tree already built from the same commit is reused.
$trees = [ordered]@{ o = $OldRevision; n = $FixedRevision }
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
$variants = @{ o = @('TABU_KOPT', 'TABU_KOPT_BINARY_VARIANCE')
               n = @('TABU_KOPT', 'TABU_KOPT_BINARY_VARIANCE', 'TABU_KOPT_INCREMENTAL_VARIANCE', 'TABU_KOPT_LOAD_BALANCE') }
$sizeList = @($Sizes -split ',' | ForEach-Object { $_.Trim() })
$workloadList = @($Workloads -split ',' | ForEach-Object { $_.Trim() })
$seedList = @($Seeds -split ',' | ForEach-Object { $_.Trim() })
$cases = @(foreach ($size in $sizeList) { foreach ($workload in $workloadList) { foreach ($seed in $seedList) { , @($size, $workload, $seed) } } })
$perCase = 0
foreach ($tree in $trees.Keys) { $perCase += $variants[$tree].Count * $BudgetMs / 1000 + 8 }
$seconds = $cases.Count * $Repetitions * $perCase + $sizeList.Count * $workloadList.Count * @($ProfileSeeds -split ',').Count * ($perCase + 6)
@{ study = 'road matrix numbering'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke
   revisions = @{ o = $OldRevision; n = $FixedRevision }; variants = $variants; jvm = $jvm; java = $version
   processors = [Environment]::ProcessorCount; budgetMs = $BudgetMs; sizes = $sizeList; workloads = $workloadList; seeds = $seedList
   fixtureSeed = $FixtureSeed; repetitions = $Repetitions; profileSeeds = @($ProfileSeeds -split ',')
   order = 'per case: tree order alternates, variant order rotates' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runs 'manifest.json') -Encoding utf8
Write-Output "$($cases.Count) cases x $Repetitions repetitions x 6 arms at $BudgetMs ms, about $([math]::Ceiling($seconds / 60)) minutes. Output: $runs"

function Invoke-Benchmark($tree, $output, $log, $properties) {
    $arguments = $jvm + @("@$($classpaths[$tree])") + $properties + @("-Dbenchmark.output=$output", "-Dbenchmark.durationMs=$BudgetMs",
        "-Dbenchmark.fixtureSeeds=$FixtureSeed", "-Dbenchmark.revision=matrix-study tree=$tree revision=$($trees[$tree])",
        'dev.waterflex.scheduler.optimizer.SolverBenchmark')
    return Invoke-Native $java $arguments $log
}

$failures = @()
$index = 0
$total = $cases.Count * $Repetitions
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    foreach ($case in $cases) {
        $index++
        $size, $workload, $seed = $case
        $order = if (($index + $repetition) % 2 -eq 0) { @('o', 'n') } else { @('n', 'o') }
        foreach ($tree in $order) {
            $list = $variants[$tree]
            $rotated = @(for ($offset = 0; $offset -lt $list.Count; $offset++) { $list[(($index + $offset) % $list.Count)] })
            $name = "rep$repetition-$size-$workload-$seed-$tree"
            Write-Output "[$index/$total] $name"
            $code = Invoke-Benchmark $tree (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.seeds=$seed", "-Dbenchmark.variants=$($rotated -join ',')")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
foreach ($tree in $trees.Keys) {
    Write-Output "[profile] tree $tree"
    $code = Invoke-Benchmark $tree (Join-Path $runs "profile-rows/profile-$tree.jsonl") (Join-Path $runs "logs/profile-$tree.log") `
        @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.seeds=$ProfileSeeds", "-Dbenchmark.variants=$($variants[$tree] -join ',')",
          "-Dbenchmark.jfr=$(Join-Path $runs "profiles/$tree")")
    if ($code -ne 0) { $failures += "profile-$tree" }
}
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = if ($Smoke) { Join-Path $runs 'smoke-summary.json' } else { $evidence }
& python (Join-Path $repository 'infra/matrix_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
