# Score and time representation study (PR F): four arms, paired per case, on an idle workstation.
#   baseline  tree s, TABU_KOPT              (the long score commit: production scoring, timing on Instant)
#   S         tree s, TABU_KOPT_LONG_SCORE   (long score levels)
#   T         tree t, TABU_KOPT              (the timing commit: nanosecond offsets)
#   T+S       tree t, TABU_KOPT_LONG_SCORE
# Each tree is frozen from its commit into -BuildRoot (a short path, as Maven needs) and built once. Every case runs
# as its own JVM per tree, both variants in it, with the tree order and the variant order alternating from case to
# case, so a slow drift in the machine shows in every arm. A JFR pass per tree follows, then the evidence file.
# Long (about 50 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-representation-study.ps1
# A two-minute harness check that writes no evidence: add -Smoke.
param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$LongScoreRevision = 'ec0efc4655532bab1f72817cf9e07e85b6ec7d4e',
    [ValidatePattern('^[0-9a-f]{40}$')][string]$TimingRevision = 'fa62792df523e020723c95350473f0e421ae3c99',
    [string]$BuildRoot = 'C:\wfrs',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    [ValidateRange(1000, 60000)][int]$DurationMs = 10000,
    # Comma-separated, as -File passes every value as text.
    [string]$Sizes = '20,50',
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

if ($Smoke) { $DurationMs = 1500; $Sizes = '20'; $Workloads = 'CLUSTERED'; $Seeds = '17'; $Repetitions = 2; $ProfileSeeds = '17' }
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
$evidence = if ($Smoke) { $null } else { Join-Path $repository "docs/evidence/solver-representation-$([DateTime]::UtcNow.ToString('yyyy-MM-dd')).json" }
if ($evidence -and (Test-Path -LiteralPath $evidence)) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $repository ".scratch/representation-study-$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows', 'profiles/s', 'profiles/t')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Study -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Study.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# Freeze and build each tree once; a tree already built from the same commit is reused.
$trees = [ordered]@{ s = $LongScoreRevision; t = $TimingRevision }
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
$variants = @('TABU_KOPT', 'TABU_KOPT_LONG_SCORE')
$sizeList = @($Sizes -split ',' | ForEach-Object { $_.Trim() })
$workloadList = @($Workloads -split ',' | ForEach-Object { $_.Trim() })
$seedList = @($Seeds -split ',' | ForEach-Object { $_.Trim() })
$cases = @(foreach ($size in $sizeList) { foreach ($workload in $workloadList) { foreach ($seed in $seedList) { , @($size, $workload, $seed) } } })
$perJvm = 2 * $DurationMs / 1000 + 8
$minutes = [math]::Ceiling(($cases.Count * $Repetitions * 2 * $perJvm + 2 * $sizeList.Count * $workloadList.Count * @($ProfileSeeds -split ',').Count * $perJvm) / 60)
@{ study = 'score and time representation'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke
   revisions = @{ s = $LongScoreRevision; t = $TimingRevision }; jvm = $jvm; java = $version; processors = [Environment]::ProcessorCount
   durationMs = $DurationMs; sizes = $sizeList; workloads = $workloadList; seeds = $seedList; fixtureSeed = $FixtureSeed
   repetitions = $Repetitions; profileSeeds = @($ProfileSeeds -split ','); order = 'per case: tree and variant order alternate' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runs 'manifest.json') -Encoding utf8
Write-Output "$($cases.Count) cases x $Repetitions repetitions x 4 arms at $DurationMs ms, about $minutes minutes. Output: $runs"

function Invoke-Benchmark($tree, $output, $log, $properties) {
    $arguments = $jvm + @("@$($classpaths[$tree])") + $properties + @("-Dbenchmark.output=$output", "-Dbenchmark.durationMs=$DurationMs",
        "-Dbenchmark.fixtureSeeds=$FixtureSeed", "-Dbenchmark.revision=representation-study tree=$tree revision=$($trees[$tree])",
        'dev.waterflex.scheduler.optimizer.SolverBenchmark')
    return Invoke-Native $java $arguments $log
}

$failures = @()
$index = 0
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    foreach ($case in $cases) {
        $index++
        $size, $workload, $seed = $case
        $order = if (($index + $repetition) % 2 -eq 0) { @('s', 't') } else { @('t', 's') }
        $variantOrder = if ($index % 2 -eq 0) { $variants } else { @($variants[1], $variants[0]) }
        foreach ($tree in $order) {
            $name = "rep$repetition-$size-$workload-$seed-$tree"
            Write-Output "[$index/$($cases.Count * $Repetitions)] $name"
            $code = Invoke-Benchmark $tree (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.seeds=$seed", "-Dbenchmark.variants=$($variantOrder -join ',')")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
foreach ($tree in $trees.Keys) {
    Write-Output "[profile] tree $tree"
    $code = Invoke-Benchmark $tree (Join-Path $runs "profile-rows/profile-$tree.jsonl") (Join-Path $runs "logs/profile-$tree.log") `
        @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.seeds=$ProfileSeeds", "-Dbenchmark.variants=$($variants -join ',')",
          "-Dbenchmark.jfr=$(Join-Path $runs "profiles/$tree")")
    if ($code -ne 0) { $failures += "profile-$tree" }
}
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = if ($Smoke) { Join-Path $runs 'smoke-summary.json' } else { $evidence }
& python (Join-Path $repository 'infra/representation_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
