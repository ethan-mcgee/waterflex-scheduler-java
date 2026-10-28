# Late-arrival buffer study: the buffer's cost to the daily search's speed, and what it buys, against production, on an
# idle workstation (infra/arrival_buffer_study.py describes the arms and holds the bars, written before the run).
#   main  tree m, TABU_KOPT, no buffer           production before the buffer
#   off   tree b, TABU_KOPT, no buffer           the buffer branch with the setting absent
#   on    tree b, TABU_KOPT, -Buffer             the buffer branch with the portal default (25 percent, 15 to 60 minutes)
# Each tree is a detached worktree of its commit under -TreeRoot (outside OneDrive), built once. A case is one fleet,
# workload, fixture seed and solver seed. Every case runs each arm as its own JVM, the arm order rotating from case to
# case, so a slow drift in the machine shows in every arm. A JFR pass per arm follows, then the evidence.
# -BranchRevision has no default: commit the buffer branch first and pass that commit.
# Run output goes to experiments/runs/<run-id>/ of the main checkout; the evidence to research/evidence/.
# Long (about 75 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-arrival-buffer-study.ps1 -BranchRevision <commit>
# A check of about 5 minutes that writes no evidence: add -Smoke.
param(
    [ValidatePattern('^[0-9a-f]{40}$')][string]$MainRevision = 'fed9565e46167a5be81688bb673752e240798b3c',
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$BranchRevision,
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # 15000 is the production default (10 s cost, 5 s fairness).
    [ValidateRange(1000, 240000)][int]$BudgetMs = 15000,
    # Comma-separated, as -File passes every value as text.
    [string]$Sizes = '10,30,50',
    [string]$Workloads = 'CLUSTERED,DISPERSED,ABSENCE,TIGHT_WINDOW,NEAR_CAPACITY',
    # Fixture seeds give different problems; solver seeds give different searches of the same problem.
    [string]$FixtureSeeds = '7001,7002',
    [string]$Seeds = '17',
    [ValidateRange(1, 5)][int]$Repetitions = 2,
    # The buffer the on arm runs and the off arm counts its stops against: percent,minMinutes,maxMinutes.
    [ValidatePattern('^\d+,\d+,\d+$')][string]$Buffer = '25,15,60',
    # The JFR pass: every size and workload at these fixture seeds, one recording per phase per solve.
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

if ($Smoke) { $BudgetMs = 1500; $Sizes = '10'; $Workloads = 'TIGHT_WINDOW,ABSENCE'; $FixtureSeeds = '7001'; $Seeds = '17'; $Repetitions = 2; $ProfileFixtureSeeds = '7001' }
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
& git -C $main merge-base --is-ancestor $MainRevision $BranchRevision
if ($LASTEXITCODE -ne 0) { throw "$BranchRevision does not build on $MainRevision; pass the buffer branch's commit" }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$evidence = if ($Smoke) { $null } else { Join-Path $main "research/evidence/arrival-buffer-$([DateTime]::UtcNow.ToString('yyyy-MM-dd')).json" }
if ($evidence -and (Test-Path -LiteralPath $evidence)) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $main "experiments/runs/arrival-buffer-$(if ($Smoke) { 'smoke-' })$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows', 'profiles/main', 'profiles/off', 'profiles/on')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

# Keep the machine awake for the run (the display may sleep).
Add-Type -Name Power -Namespace Study -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
[Study.Power]::SetThreadExecutionState([uint32]'0x80000001') | Out-Null

# Freeze each tree as a detached worktree of its commit and build it once; a tree already built from it is reused.
$trees = [ordered]@{ m = $MainRevision; b = $BranchRevision }
$classpaths = @{}
foreach ($tree in $trees.Keys) {
    $revision = $trees[$tree]
    $directory = Join-Path $TreeRoot "arrival-buffer-$tree"
    $built = Join-Path $directory 'scheduler-service\target\study-built.txt'
    if ((Test-Path -LiteralPath $built) -and ((Get-Content -LiteralPath $built -Raw).Trim() -eq $revision)) {
        Write-Output "[$tree] reusing the build of $revision in $directory"
    } else {
        if (Test-Path -LiteralPath $directory) { throw "$directory exists and is not a build of $revision; remove it (git worktree remove) or choose another -TreeRoot" }
        $code = Invoke-Native git @('-C', $main, 'worktree', 'add', '--detach', $directory, $revision) (Join-Path $runs "logs/worktree-$tree.log")
        if ($code -ne 0) { throw "git worktree add failed for $revision (see logs/worktree-$tree.log)" }
        Write-Output "[$tree] building $revision in $directory"
        $code = Invoke-Native (Join-Path $directory 'mvnw.cmd') @('-q', '-f', (Join-Path $directory 'pom.xml'), '-pl', 'scheduler-service', '-am', '-DskipTests',
            'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/study-classpath.txt', '-DexcludeGroupIds=dev.waterflex') (Join-Path $runs "logs/build-$tree.log")
        if ($code -ne 0) { throw "Build of tree $tree failed (see logs/build-$tree.log)" }
        Set-Content -LiteralPath $built -Value $revision -Encoding ascii
    }
    $head = (& git -C $directory rev-parse HEAD).Trim()
    if ($head -ne $revision) { throw "$directory is at $head, not $revision" }
    $dependencies = (Get-Content -LiteralPath (Join-Path $directory 'scheduler-service/target/study-classpath.txt') -Raw).Trim()
    # The engine must come from this tree's own classes, never a jar installed in the local Maven repository.
    if ($dependencies -match 'calculation-engine') { throw "Tree $tree resolved calculation-engine as a jar: $dependencies" }
    $own = @('scheduler-service\target\test-classes', 'scheduler-service\target\classes', 'calculation-engine\target\classes') | ForEach-Object { Join-Path $directory $_ }
    # An argument file keeps the long classpath off the command line.
    $arguments = Join-Path $runs "classpath-$tree.args"
    Set-Content -LiteralPath $arguments -Value ('-cp "' + (($own + $dependencies) -join ';').Replace('\', '\\') + '"') -Encoding ascii
    $classpaths[$tree] = $arguments
}

$jvm = @('-Xmx1536m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC')
# Each arm: its tree and the property that sets its buffer. Main predates both properties and gets none.
$arms = [ordered]@{ main = @{ tree = 'm'; property = '' }; off = @{ tree = 'b'; property = "-Dbenchmark.reportArrivalBuffer=$Buffer" }
                    on = @{ tree = 'b'; property = "-Dbenchmark.arrivalBuffer=$Buffer" } }
$armNames = @($arms.Keys)
$sizeList = @($Sizes -split ',' | ForEach-Object { $_.Trim() })
$workloadList = @($Workloads -split ',' | ForEach-Object { $_.Trim() })
$fixtureList = @($FixtureSeeds -split ',' | ForEach-Object { $_.Trim() })
$seedList = @($Seeds -split ',' | ForEach-Object { $_.Trim() })
$profileFixtureList = @($ProfileFixtureSeeds -split ',' | ForEach-Object { $_.Trim() })
$cases = @(foreach ($size in $sizeList) { foreach ($workload in $workloadList) { foreach ($fixture in $fixtureList) { foreach ($seed in $seedList) {
    , @($size, $workload, $fixture, $seed) } } } })
# Each solve takes its budget and about 5 s more (JVM start, warmup, fixture, validation).
$perSolve = $BudgetMs / 1000 + 5
$seconds = $cases.Count * $Repetitions * $armNames.Count * $perSolve + $sizeList.Count * $workloadList.Count * $profileFixtureList.Count * $seedList.Count * $armNames.Count * $perSolve
@{ study = 'late-arrival buffer'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke
   revisions = @{ m = $MainRevision; b = $BranchRevision }; arms = @{ main = 'tree m, no buffer'; off = 'tree b, no buffer'; on = "tree b, buffer $Buffer" }
   arrivalBuffer = $Buffer; jvm = $jvm; java = $version; processors = [Environment]::ProcessorCount; budgetMs = $BudgetMs
   sizes = $sizeList; workloads = $workloadList; fixtureSeeds = $fixtureList; seeds = $seedList; repetitions = $Repetitions
   profileFixtureSeeds = $profileFixtureList; order = 'per case: each arm its own JVM, arm order rotating' } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runs 'manifest.json') -Encoding utf8
Write-Output "$($cases.Count) cases x $Repetitions repetitions x $($armNames.Count) arms at $BudgetMs ms, about $([math]::Ceiling($seconds / 60)) minutes. Output: $runs"

function Invoke-Benchmark($arm, $output, $log, $properties) {
    $tree = $arms[$arm].tree
    $armProperties = @(if ($arms[$arm].property) { $arms[$arm].property })
    $arguments = $jvm + @("@$($classpaths[$tree])") + $armProperties + $properties + @("-Dbenchmark.output=$output", "-Dbenchmark.durationMs=$BudgetMs",
        '-Dbenchmark.variants=TABU_KOPT', "-Dbenchmark.revision=arrival-buffer-study arm=$arm revision=$($trees[$tree])",
        'dev.waterflex.scheduler.optimizer.SolverBenchmark')
    return Invoke-Native $java $arguments $log
}

$failures = @()
$index = 0
$total = $cases.Count * $Repetitions
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    foreach ($case in $cases) {
        $index++
        $size, $workload, $fixture, $seed = $case
        $order = @(for ($offset = 0; $offset -lt $armNames.Count; $offset++) { $armNames[(($index + $repetition + $offset) % $armNames.Count)] })
        foreach ($arm in $order) {
            $name = "rep$repetition-$size-$workload-$fixture-$seed-$arm"
            Write-Output "[$index/$total] $name"
            $code = Invoke-Benchmark $arm (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.fixtureSeeds=$fixture", "-Dbenchmark.seeds=$seed")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
foreach ($arm in $armNames) {
    Write-Output "[profile] $arm"
    $code = Invoke-Benchmark $arm (Join-Path $runs "profile-rows/profile-$arm.jsonl") (Join-Path $runs "logs/profile-$arm.log") `
        @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.fixtureSeeds=$ProfileFixtureSeeds", "-Dbenchmark.seeds=$Seeds",
          "-Dbenchmark.jfr=$(Join-Path $runs "profiles/$arm")")
    if ($code -ne 0) { $failures += "profile-$arm" }
}
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = if ($Smoke) { Join-Path $runs 'smoke-summary.json' } else { $evidence }
& python (Join-Path $repository 'infra/arrival_buffer_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
Write-Output "The trees stay in $TreeRoot for a rerun; remove them with: git -C $main worktree remove $(Join-Path $TreeRoot 'arrival-buffer-m') and the same for arrival-buffer-b"
