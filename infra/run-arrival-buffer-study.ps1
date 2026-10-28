# Late-arrival buffer study: the buffer's cost to the daily search's speed, and what it buys, against production, on an
# idle workstation (infra/arrival_buffer_study.py describes the arms and holds the bars, written before the run).
#   main  tree m, TABU_KOPT, no buffer           production before the buffer
#   off   tree b, TABU_KOPT, no buffer           the buffer branch with the setting absent
#   on    tree b, TABU_KOPT, -Buffer             the buffer branch with the portal default (25 percent, 15 to 60 minutes)
# Tree m is main plus a test-only change (bench/arrival-buffer-main) that lets the fixtures take 4- and 6-hour windows,
# placed exactly as the buffer branch places them; no production code differs from main.
# Each tree is a detached worktree of its commit under -TreeRoot (outside OneDrive), built once. A case is one fleet,
# window length, workload, fixture seed and solver seed. Every case runs each arm as its own JVM, the arm order rotating
# from case to case, so a slow drift in the machine shows in every arm. A JFR pass per arm follows, then the evidence.
# -BranchRevision has no default: commit the buffer branch first and pass that commit.
# Run output goes to experiments/runs/<run-id>/ of the main checkout; the evidence to research/evidence/.
# Long (about 4 hours 20 minutes with the defaults); the owner starts it:
#   powershell -ExecutionPolicy Bypass -File infra/run-arrival-buffer-study.ps1 -BranchRevision <commit>
# A check of about 5 minutes that writes no evidence: add -Smoke. Parameters given with it override its defaults.
param(
    # bench/arrival-buffer-main: main fed9565 plus the test-only window option.
    [ValidatePattern('^[0-9a-f]{40}$')][string]$MainRevision = '170ab77001214d6b23d4e8c7ae43b76e5d14541d',
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{40}$')][string]$BranchRevision,
    [string]$TreeRoot = 'C:\Projects\waterflex-worktrees',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25',
    # 15000 is the production default (10 s cost, 5 s fairness).
    [ValidateRange(1000, 240000)][int]$BudgetMs = 15000,
    # Comma-separated, as -File passes every value as text.
    [string]$Sizes = '5,10,30,50',
    [string]$Workloads = 'CLUSTERED,DISPERSED,ABSENCE,TIGHT_WINDOW,NEAR_CAPACITY',
    # Arrival window lengths in whole hours (1 to 9). Each window keeps the fixture's start unless it would end after
    # 17:00; 2 is the fixtures' own length.
    [string]$WindowHours = '2,4,6',
    # Fixture seeds give different problems; solver seeds give different searches of the same problem.
    [string]$FixtureSeeds = '7001,7002',
    [string]$Seeds = '17',
    [ValidateRange(1, 5)][int]$Repetitions = 2,
    # The buffer the on arm runs and the off arm counts its stops against: percent,minMinutes,maxMinutes.
    [ValidatePattern('^\d+,\d+,\d+$')][string]$Buffer = '25,15,60',
    # The JFR pass: every size and workload at these fixture seeds and window lengths, one recording per phase per solve.
    # 4 hours is the portal's default window.
    [string]$ProfileFixtureSeeds = '7001',
    [string]$ProfileWindowHours = '4',
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

# A smoke's own defaults; a parameter given on the command line keeps its value (a speed check at the full budget).
if ($Smoke) {
    $smokeDefaults = @{ BudgetMs = 1500; Sizes = '5,10'; Workloads = 'TIGHT_WINDOW,ABSENCE'; WindowHours = '2,6'; FixtureSeeds = '7001'; Seeds = '17'
                        Repetitions = 2; ProfileFixtureSeeds = '7001'; ProfileWindowHours = '6' }
    foreach ($name in $smokeDefaults.Keys) { if (-not $PSBoundParameters.ContainsKey($name)) { Set-Variable -Name $name -Value $smokeDefaults[$name] } }
}
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
# Tree m must be the branch's production base with test-only changes, so the main arm runs production code unchanged.
$base = (& git -C $main merge-base $MainRevision $BranchRevision).Trim()
if ($LASTEXITCODE -ne 0) { throw "$MainRevision and $BranchRevision share no history" }
$changed = @(& git -C $main diff --name-only $base $MainRevision)
$production = @($changed | Where-Object { $_ -notmatch '/src/test/' })
if ($production.Count -gt 0) { throw "$MainRevision changes production code over $base ($($production -join ', ')); tree m must differ from it only in tests" }
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')
$evidence = if ($Smoke) { $null } else { Join-Path $main "research/evidence/arrival-buffer-$([DateTime]::UtcNow.ToString('yyyy-MM-dd')).json" }
if ($evidence -and (Test-Path -LiteralPath $evidence)) { throw "Evidence already exists: $evidence" }
$runs = Join-Path $main "experiments/runs/arrival-buffer-$(if ($Smoke) { 'smoke-' })$stamp"
foreach ($directory in @('rows', 'logs', 'profile-rows')) { New-Item -ItemType Directory -Force (Join-Path $runs $directory) | Out-Null }

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
$windowList = @($WindowHours -split ',' | ForEach-Object { [int]$_.Trim() })
$profileFixtureList = @($ProfileFixtureSeeds -split ',' | ForEach-Object { $_.Trim() })
$profileWindowList = @($ProfileWindowHours -split ',' | ForEach-Object { [int]$_.Trim() })
if (@($windowList + $profileWindowList | Where-Object { $_ -lt 1 -or $_ -gt 9 }).Count -gt 0) { throw '-WindowHours and -ProfileWindowHours are whole hours from 1 to 9' }
$cases = @(foreach ($size in $sizeList) { foreach ($hours in $windowList) { foreach ($workload in $workloadList) { foreach ($fixture in $fixtureList) {
    foreach ($seed in $seedList) { , @($size, $workload, $fixture, $seed, $hours) } } } } })
# Each solve takes its budget and about 5 s more (JVM start, warmup, fixture, validation).
$perSolve = $BudgetMs / 1000 + 5
$seconds = $cases.Count * $Repetitions * $armNames.Count * $perSolve +
    $sizeList.Count * $workloadList.Count * $profileFixtureList.Count * $seedList.Count * $profileWindowList.Count * $armNames.Count * $perSolve
@{ study = 'late-arrival buffer'; startedAt = [DateTime]::UtcNow.ToString('o'); smoke = [bool]$Smoke
   revisions = @{ m = $MainRevision; b = $BranchRevision }; arms = @{ main = 'tree m, no buffer'; off = 'tree b, no buffer'; on = "tree b, buffer $Buffer" }
   arrivalBuffer = $Buffer; jvm = $jvm; java = $version; processors = [Environment]::ProcessorCount; budgetMs = $BudgetMs
   sizes = $sizeList; windowHours = $windowList; workloads = $workloadList; fixtureSeeds = $fixtureList; seeds = $seedList; repetitions = $Repetitions
   profileFixtureSeeds = $profileFixtureList; profileWindowHours = $profileWindowList; order = 'per case: each arm its own JVM, arm order rotating' } |
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
        $size, $workload, $fixture, $seed, $hours = $case
        $order = @(for ($offset = 0; $offset -lt $armNames.Count; $offset++) { $armNames[(($index + $repetition + $offset) % $armNames.Count)] })
        foreach ($arm in $order) {
            $name = "rep$repetition-$size-$workload-$fixture-$seed-$($hours)h-$arm"
            Write-Output "[$index/$total] $name"
            $code = Invoke-Benchmark $arm (Join-Path $runs "rows/$name.jsonl") (Join-Path $runs "logs/$name.log") `
                @("-Dbenchmark.sizes=$size", "-Dbenchmark.workloads=$workload", "-Dbenchmark.fixtureSeeds=$fixture", "-Dbenchmark.seeds=$seed",
                  "-Dbenchmark.windowHours=$hours")
            if ($code -ne 0) { $failures += $name }
        }
    }
}
foreach ($hours in $profileWindowList) {
    foreach ($arm in $armNames) {
        Write-Output "[profile] $arm, $hours-hour windows"
        # Recordings are named by case and phase only, so each window length gets its own folder.
        $recordings = Join-Path $runs "profiles/$arm/$($hours)h"
        $code = Invoke-Benchmark $arm (Join-Path $runs "profile-rows/profile-$arm-$($hours)h.jsonl") (Join-Path $runs "logs/profile-$arm-$($hours)h.log") `
            @("-Dbenchmark.sizes=$Sizes", "-Dbenchmark.workloads=$Workloads", "-Dbenchmark.fixtureSeeds=$ProfileFixtureSeeds", "-Dbenchmark.seeds=$Seeds",
              "-Dbenchmark.windowHours=$hours", "-Dbenchmark.jfr=$recordings")
        if ($code -ne 0) { $failures += "profile-$arm-$($hours)h" }
    }
}
[Study.Power]::SetThreadExecutionState([uint32]'0x80000000') | Out-Null
if ($failures.Count -gt 0) { throw "Runs failed (see their logs): $($failures -join ', ')" }

$out = if ($Smoke) { Join-Path $runs 'smoke-summary.json' } else { $evidence }
& python (Join-Path $repository 'infra/arrival_buffer_study.py') summarize --run $runs --out $out --jfr $jfr
if ($LASTEXITCODE -ne 0) { throw 'The summary failed; the raw runs are kept in ' + $runs }
Write-Output "Done. Evidence: $out"
Write-Output "The trees stay in $TreeRoot for a rerun; remove them with: git -C $main worktree remove $(Join-Path $TreeRoot 'arrival-buffer-m') and the same for arrival-buffer-b"
