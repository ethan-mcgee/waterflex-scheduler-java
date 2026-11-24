# The VM performance test (infra/vm-perf/README.md): drive times (test 1) and booking and daily capacity (test 2)
# against the scheduler VM over its public API, from this workstation, with the VM's own stage, sampler and restore
# scripts run over SSH. research/vm-performance-2026-11/start.ps1 starts it; nothing here asks for input.
#
#   -Mode DryRun   prints every stage and cell with its settings and expected time, and changes nothing
#   -Mode Smoke    both tests in miniature, one stage change and the restore (about 15 minutes)
#   -Mode Full     the whole matrix (about 8 hours)
#
# The token is read from $env:<TokenVariable> (never a file). Run again with the same -Run to resume: a cell with a
# cell.json is done and skipped. Whatever happens, the finally block saves the last stage's logs, restores the VM's
# live layout, copies what is left and summarizes. The VM also restores itself after -DeadmanHours (vm-arm.sh).
#
# -Local rehearses the whole pipeline against the local VM rehearsal stack (deploy/vm/rehearsal.override.yml at
# 127.0.0.1:18080): the same VM scripts run through Git Bash in .scratch/vm-perf-rehearsal instead of over SSH.
param(
    [ValidateSet('DryRun', 'Smoke', 'Full')][string]$Mode = 'DryRun',
    [string]$Run = 'experiments/runs/vm-performance-2026-11',
    [string]$Vm = 'ethan@192.168.20.171',
    [string]$Url = 'https://192.168.20.171',
    # The workstation reaches the VM over a VPN that passes SSH but not HTTPS (found 2026-11-24), so the API is reached
    # through the SSH tunnel by default; Direct calls -Url as given.
    [ValidateSet('Tunnel', 'Direct')][string]$ApiPath = 'Tunnel',
    [string]$TokenVariable = 'SCHEDULER_STUDY_TOKEN',
    [string]$RequestFile = '.scratch/vm-drive-times/out/geometry-request-2024-01-02.json',
    [string]$Metro = 'metro-paso-robles',
    [string]$TimeZone = 'America/Los_Angeles',
    # Real job addresses of the imported Paso Robles backtest days: generated days and jobs are drawn from them, so they
    # route as the dealer's do (random points in the dealer's box often land out of a road's reach).
    [string]$PointsGlob = 'experiments/runs/backtest-paso-robles-2026-11*/variants/*/days/*/api-request.json',
    [ValidateSet('drive', 'capacity')][string[]]$Tests = @('drive', 'capacity'),
    [ValidateRange(1, 24)][int]$DeadmanHours = 11,
    [string]$CheckScript = '.scratch/vm-drive-times/check-vm-live.sh',
    [switch]$Local
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Import-Module (Join-Path $repo 'infra/study-token.psm1') -Force
# Through the tunnel the API is https://127.0.0.1:18443, a name the VM's certificate holds.
$apiTarget = '{0}:{1}' -f ([uri]$Url).Host, ([uri]$Url).Port
$tunnelApi = $ApiPath -eq 'Tunnel' -and -not $Local
if ($tunnelApi) { $Url = 'https://127.0.0.1:18443' }
$kit = Join-Path $repo 'infra/host-conformance'
$runDir = if ([IO.Path]::IsPathRooted($Run)) { $Run } else { Join-Path $repo $Run }
$request = if ([IO.Path]::IsPathRooted($RequestFile)) { $RequestFile } else { Join-Path $repo $RequestFile }
$gitBash = 'C:\Program Files\Git\bin\bash.exe'
$localHome = Join-Path $repo '.scratch/vm-perf-rehearsal'
$points = Join-Path $runDir 'points.json'
$bookingMetrics = 'http://127.0.0.1:19010/actuator/prometheus'
$dailyMetrics = 'http://127.0.0.1:19011/actuator/prometheus'

# ---------------------------------------------------------------------------------------------------------------- matrix
# A cell is one tool run (routes-load.ts or load.ts), or a pair of them started together. Seconds is the expected time,
# for the dry run's total and the hang guard (twice it plus five minutes). Warm-up cells are kept but not summarized.
$common = @('--url', $Url, '--token-env', $TokenVariable, '--metro', $Metro, '--time-zone', $TimeZone)
$policy = @('--daily-search', 'TABU_KOPT', '--cost-seconds', '10', '--fairness-seconds', '5', '--fairness-budget', '0.02')
function Routes([string]$Name, [int]$Seconds, [string[]]$Arguments, [switch]$Warmup) {
    @{ Name = $Name; Seconds = $Seconds; Warmup = [bool]$Warmup; Runs = @(@{ Tool = 'routes'; Args = $Arguments }) }
}
function Load([string]$Name, [int]$Seconds, [string[]]$Arguments, [switch]$Warmup) {
    @{ Name = $Name; Seconds = $Seconds; Warmup = [bool]$Warmup; Runs = @(@{ Tool = 'load'; Args = $Arguments }) }
}
function Pair([string]$Name, [int]$Seconds, [string[]]$LoadArguments, [string[]]$RoutesArguments) {
    @{ Name = $Name; Seconds = $Seconds; Warmup = $false; Runs = @(@{ Tool = 'load'; Args = $LoadArguments }, @{ Tool = 'routes'; Args = $RoutesArguments }) }
}
$real = @('--request-file', $request, '--points-file', $points)
function Generated([int]$Technicians) { @('--points-file', $points, '--technicians', "$Technicians") }
function Booking([int]$Desks, [int]$Fleet, [int]$Seconds) {
    @('--scenario', 'booking', '--points-file', $points, '--technicians', "$Fleet", '--appointments', '2', '--concurrency', "$Desks", '--date-spread',
        '--duration-s', "$Seconds", '--metrics-url', $bookingMetrics) + $policy
}
# Three visits per technician: with the dealer's real distances a fourth often pushes a technician past 17:00 in the
# as-given order, and the scheduler refuses a day whose current routes do not hold (rehearsal, 2026-11-24).
function DailySync([int]$Fleet, [int]$Proposals) {
    @('--scenario', 'daily', '--daily-mode', 'sync', '--concurrency', '1', '--points-file', $points, '--daily-technicians', "$Fleet", '--appointments', '3',
        '--daily-proposals', "$Proposals", '--duration-s', '1200', '--metrics-url', $dailyMetrics) + $policy
}
function DailyQueued([int]$Planners, [int]$Fleet, [int]$Seconds) {
    @('--scenario', 'daily', '--daily-mode', 'async', '--concurrency', "$Planners", '--points-file', $points, '--daily-technicians', "$Fleet", '--appointments', '3',
        '--duration-s', "$Seconds", '--metrics-url', $dailyMetrics) + $policy
}
function Mixed([int]$Desks, [int]$Seconds) {
    @('--scenario', 'mixed', '--concurrency', "$Desks", '--daily-concurrency', '1', '--daily-mode', 'async', '--points-file', $points, '--technicians', '10',
        '--daily-technicians', '20', '--appointments', '3', '--date-spread', '--duration-s', "$Seconds", '--metrics-url', $bookingMetrics) + $policy
}
# Dispatchers each moving every 10 s (open the day, pick a job, drop it), on the real day.
function Dispatchers([int]$Callers, [int]$Seconds) { @('--mode', 'session', '--placement', 'fresh-job', '--callers', "$Callers", '--pace-ms', '10000', '--duration-s', "$Seconds") + $real }
function Solves([string]$Stage) { "SCHEDULER_STUDY_PROFILES=/profiles/$Stage/solves" }

function Get-Stages {
    $stages = New-Object System.Collections.Generic.List[object]
    if ($Mode -eq 'Smoke') {
        $stages.Add(@{ Name = 'smoke-live'; Test = 'drive'; Settings = @(); Cells = @(
            (Routes 'warmup' 60 (@('--mode', 'geometry', '--moves', '5', '--duration-s', '60') + $real) -Warmup),
            (Routes 'geometry-real' 120 (@('--mode', 'geometry', '--moves', '10', '--duration-s', '120') + $real)),
            (Routes 'insertions-6t' 120 (@('--mode', 'insertions', '--placement', 'fresh-job', '--moves', '10', '--duration-s', '120') + (Generated 6))),
            (Routes 'session-real' 120 (@('--mode', 'session', '--moves', '5', '--duration-s', '120') + $real)),
            (Routes 'paced-2' 30 (Dispatchers 2 30)),
            (Load 'booking-1x5t' 30 (Booking 1 5 30)),
            (Load 'daily-sync-5t' 120 (DailySync 5 1)),
            (Pair 'mixed-1' 30 (Mixed 1 30) (Dispatchers 1 30))) })
        $stages.Add(@{ Name = 'smoke-gate2'; Test = 'drive'; Settings = @('SCHEDULER_BOOKING_SEARCH_CAPACITY=2', 'SCHEDULER_BOOKING_BACKGROUND_LIMIT=2', (Solves 'smoke-gate2')); Cells = @(
            (Routes 'ladder-geometry-2' 30 (@('--mode', 'geometry', '--callers', '2', '--duration-s', '30') + $real)),
            (Load 'daily-sync-5t' 120 (DailySync 5 1))) })
        return $stages
    }
    if ($Tests -contains 'drive') {
        # Test 1 on the live caps: one dispatcher by day size, the drag session, paced dispatchers, the saturation ladder.
        $cells = New-Object System.Collections.Generic.List[object]
        $cells.Add((Routes 'warmup' 60 (@('--mode', 'geometry', '--duration-s', '60') + $real) -Warmup))
        $cells.Add((Routes 'geometry-real' 300 (@('--mode', 'geometry', '--moves', '200', '--duration-s', '300') + $real)))
        foreach ($size in 3, 6, 12, 25) { $cells.Add((Routes "geometry-${size}t" 300 (@('--mode', 'geometry', '--moves', '200', '--duration-s', '300') + (Generated $size)))) }
        foreach ($placement in 'fresh-job', 'repeat') { $cells.Add((Routes "insertions-real-$placement" 180 (@('--mode', 'insertions', '--placement', $placement, '--moves', '200', '--duration-s', '180') + $real))) }
        foreach ($size in 3, 6, 12, 25) {
            foreach ($placement in 'fresh-day', 'fresh-job', 'repeat') {
                $cells.Add((Routes "insertions-${size}t-$placement" 180 (@('--mode', 'insertions', '--placement', $placement, '--moves', '200', '--duration-s', '180') + (Generated $size))))
            }
        }
        $cells.Add((Routes 'session-real' 300 (@('--mode', 'session', '--moves', '100', '--duration-s', '300') + $real)))
        $cells.Add((Routes 'session-12t' 300 (@('--mode', 'session', '--moves', '100', '--duration-s', '300') + (Generated 12))))
        $ladder = {
            param($list)
            foreach ($callers in 1, 2, 5, 10, 20) { $list.Add((Routes "paced-$callers" 300 (Dispatchers $callers 300))) }
            foreach ($callers in 1, 2, 4, 8, 16, 32) {
                $list.Add((Routes "ladder-geometry-$callers" 120 (@('--mode', 'geometry', '--callers', "$callers", '--duration-s', '120') + $real)))
                $list.Add((Routes "ladder-insertions-$callers" 120 (@('--mode', 'insertions', '--placement', 'fresh-job', '--callers', "$callers", '--duration-s', '120') + (Generated 12))))
            }
        }
        & $ladder $cells
        $stages.Add(@{ Name = 'drive-live'; Test = 'drive'; Settings = @(); Cells = $cells.ToArray() })
        # The same dispatchers and ladder with two route calls allowed at once: is the admission gate or the CPU the limit?
        $gate = New-Object System.Collections.Generic.List[object]
        $gate.Add((Routes 'warmup' 60 (@('--mode', 'geometry', '--duration-s', '60') + $real) -Warmup))
        & $ladder $gate
        $stages.Add(@{ Name = 'drive-gate2'; Test = 'drive'; Settings = @('SCHEDULER_BOOKING_SEARCH_CAPACITY=2', 'SCHEDULER_BOOKING_BACKGROUND_LIMIT=2'); Cells = $gate.ToArray() })
    }
    if ($Tests -contains 'capacity') {
        $warm = (Load 'warmup' 120 (Booking 1 10 120) -Warmup)
        $booking = New-Object System.Collections.Generic.List[object]
        $booking.Add($warm)
        foreach ($fleet in 5, 10, 15) { foreach ($desks in 1, 2, 4) { $booking.Add((Load "booking-${desks}x${fleet}t" 300 (Booking $desks $fleet 300))) } }
        $stages.Add(@{ Name = 'cap-booking'; Test = 'capacity'; Settings = @(); Cells = $booking.ToArray() })

        $dailyCells = {
            param($list, [int[]]$Fleets)
            foreach ($fleet in $Fleets) { $list.Add((Load "daily-sync-${fleet}t" 300 (DailySync $fleet 10))) }
            foreach ($planners in 2, 4, 8) { $list.Add((Load "daily-queued-${planners}x20t" 600 (DailyQueued $planners 20 600))) }
        }
        $daily = New-Object System.Collections.Generic.List[object]
        $daily.Add((Load 'warmup' 240 (DailySync 10 3) -Warmup))
        & $dailyCells $daily @(5, 10, 20, 50)
        $stages.Add(@{ Name = 'cap-daily'; Test = 'capacity'; Settings = @((Solves 'cap-daily')); Cells = $daily.ToArray() })

        $mixedCells = {
            param($list)
            foreach ($desks in 2, 4, 8) { $list.Add((Pair "mixed-$desks" 600 (Mixed $desks 600) (Dispatchers 5 600))) }
        }
        $mixed = New-Object System.Collections.Generic.List[object]
        $mixed.Add($warm)
        & $mixedCells $mixed
        $stages.Add(@{ Name = 'cap-mixed'; Test = 'capacity'; Settings = @(); Cells = $mixed.ToArray() })

        # Caps moved inside the same two vCPU: does a solve speed up with its cap (the cap binds) or not (the host does)?
        foreach ($variant in @(
                @{ Name = 'cap-daily1'; Settings = @('SCHEDULER_DAILY_CPUS=1', 'SCHEDULER_BOOKING_CPUS=0.5') },
                @{ Name = 'cap-all2'; Settings = @('SCHEDULER_DAILY_CPUS=2', 'SCHEDULER_BOOKING_CPUS=2', 'ROUTING_CPUS=2') })) {
            $list = New-Object System.Collections.Generic.List[object]
            $list.Add((Load 'warmup' 240 (DailySync 10 3) -Warmup))
            $list.Add((Load 'daily-sync-20t' 240 (DailySync 20 10)))
            $list.Add((Load 'daily-queued-4x20t' 600 (DailyQueued 4 20 600)))
            $list.Add((Pair 'mixed-4' 600 (Mixed 4 600) (Dispatchers 5 600)))
            $stages.Add(@{ Name = $variant.Name; Test = 'capacity'; Settings = @($variant.Settings + (Solves $variant.Name)); Cells = $list.ToArray() })
        }

        # An hour of steady mixed load on the live caps, fixed before the run: drift in heap, latency and disk.
        $stages.Add(@{ Name = 'cap-soak'; Test = 'capacity'; Settings = @(); Cells = @($warm, (Pair 'soak-60m' 3600 (Mixed 2 3600) (Dispatchers 2 3600))) })
    }
    return $stages
}

# ----------------------------------------------------------------------------------------------------------- the VM side
function Invoke-Vm([string]$Command, [switch]$AllowFailure) {
    $log = Join-Path $runDir 'logs/vm.log'
    Add-Content -Path $log -Value "`n# $(Get-Date -Format o) $Command"
    $ErrorActionPreference = 'Continue'
    if ($Local) {
        $env:PERF_HOME = $localHome.Replace('\', '/'); $env:CHECKOUT = $repo.Replace('\', '/'); $env:PERF_PORTS = '0'
        $env:LIVE_ENV = (Join-Path $repo 'deploy/vm/rehearsal.env').Replace('\', '/')
        $env:LIVE_COMPOSE_FILES = "$((Join-Path $repo 'deploy/vm/docker-compose.yml').Replace('\', '/')),$((Join-Path $repo 'deploy/vm/rehearsal.override.yml').Replace('\', '/'))"
        $script = $Command -replace '~/perf-test', $env:PERF_HOME
        $output = & $gitBash -c $script 2>&1 | Out-String
    } else {
        $output = & ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=30 $Vm $Command 2>&1 | Out-String
    }
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Add-Content -Path $log -Value $output
    if ($code -ne 0 -and -not $AllowFailure) { throw "On the VM: '$Command' failed with exit $code`n$output" }
    return $output
}

# Copies a file or folder of ~/perf-test into the Destination folder (a folder lands as Destination/<its name>, as scp -r
# and Copy-Item -Recurse both nest it). One the VM does not have is not an error: there was nothing to copy.
function Copy-FromVm([string]$RemotePath, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $exists = Invoke-Vm "test -e ~/perf-test/$RemotePath && echo yes || echo no"
    if ($exists.Trim() -ne 'yes') { return $false }
    if ($Local) {
        Copy-Item -Recurse -Force (Join-Path $localHome $RemotePath) $Destination
    } else {
        $ErrorActionPreference = 'Continue'
        & scp -o BatchMode=yes -r -q "${Vm}:perf-test/$RemotePath" $Destination 2>&1 | Add-Content (Join-Path $runDir 'logs/vm.log')
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -ne 0) { throw "Could not copy ~/perf-test/$RemotePath from the VM (scp exit $code)" }
    }
    return $true
}

function Copy-ToVm {
    $files = Get-ChildItem (Join-Path $repo 'infra/vm-perf') -File | Where-Object { $_.Extension -in '.sh', '.yml' }
    $check = if ([IO.Path]::IsPathRooted($CheckScript)) { $CheckScript } else { Join-Path $repo $CheckScript }
    if ($Local) {
        New-Item -ItemType Directory -Force -Path $localHome | Out-Null
        $files | Copy-Item -Destination $localHome -Force
        return
    }
    Invoke-Vm 'mkdir -p ~/perf-test' | Out-Null
    $paths = @($files | ForEach-Object { $_.FullName })
    if (Test-Path $check) { $paths += $check }
    $ErrorActionPreference = 'Continue'
    & scp -o BatchMode=yes -q @paths "${Vm}:perf-test/" 2>&1 | Add-Content (Join-Path $runDir 'logs/vm.log')
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Could not copy the test's scripts to the VM (scp exit $code)" }
}

# The SSH tunnel to the replicas' metrics ports, published on the VM's loopback by perf.ports.yml, and with -ApiPath
# Tunnel to the public API. Checked before every cell, so a tunnel that dropped is started again.
$script:tunnel = $null
function Confirm-Tunnel {
    if ($Local) { return }
    if ($null -ne $script:tunnel -and -not $script:tunnel.HasExited) { return }
    $arguments = @('-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=30', '-L', '19010:127.0.0.1:19010', '-L', '19011:127.0.0.1:19011')
    if ($tunnelApi) { $arguments += @('-L', "18443:$apiTarget") }
    $script:tunnel = Start-Process -FilePath 'ssh' -PassThru -WindowStyle Hidden -ArgumentList ($arguments + $Vm)
    Start-Sleep -Seconds 3
    if ($script:tunnel.HasExited) { throw "The SSH tunnel to the VM did not stay up (ports 19010, 19011 or 18443 in use here?)" }
}

# Windows must not sleep while the run is out of the room.
Add-Type -Namespace VmPerf -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
function Set-KeepAwake([bool]$On) {
    $flags = if ($On) { [uint32]'0x80000001' } else { [uint32]'0x80000000' }
    [VmPerf.Power]::SetThreadExecutionState($flags) | Out-Null
}

# ------------------------------------------------------------------------------------------------------------- the cells
function Format-Argument([string]$Value) { if ($Value -match '[\s"]') { '"' + ($Value -replace '"', '\"') + '"' } else { $Value } }

function Test-StageDone($Stage) {
    foreach ($cell in $Stage.Cells) { if (-not (Test-Path (Join-Path $runDir "stages/$($Stage.Name)/cells/$($cell.Name)/cell.json"))) { return $false } }
    return $true
}

function Invoke-Cell($Stage, $Cell) {
    $dir = Join-Path $runDir "stages/$($Stage.Name)/cells/$($Cell.Name)"
    if (Test-Path (Join-Path $dir 'cell.json')) { Write-Host "  done already: $($Cell.Name)"; return }
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Confirm-Tunnel
    $started = Get-Date
    Write-Host ("  {0} {1} (about {2} min)" -f $started.ToString('HH:mm'), $Cell.Name, [math]::Ceiling($Cell.Seconds / 60))
    $processes = @()
    $index = 0
    foreach ($part in $Cell.Runs) {
        $index++
        $entry = if ($part.Tool -eq 'routes') { 'routes-load.ts' } else { 'load.ts' }
        $arguments = @('tsx', $entry) + $common + $part.Args + @('--label', "$($Stage.Name)/$($Cell.Name)", '--out', $dir)
        $processes += [pscustomobject]@{ Tool = $part.Tool; Args = $part.Args
            Process = Start-Process -FilePath 'npx.cmd' -WorkingDirectory $kit -NoNewWindow -PassThru `
                -ArgumentList (($arguments | ForEach-Object { Format-Argument $_ }) -join ' ') `
                -RedirectStandardOutput (Join-Path $dir "$index-$($part.Tool).out.log") -RedirectStandardError (Join-Path $dir "$index-$($part.Tool).err.log") }
        # Windows PowerShell keeps a started process's exit code only if its handle was opened while it ran.
        $null = $processes[-1].Process.Handle
    }
    $limitMs = ($Cell.Seconds * 2 + 300) * 1000
    $results = foreach ($item in $processes) {
        $finished = $item.Process.WaitForExit([math]::Max(1000, $limitMs - [int]((Get-Date) - $started).TotalMilliseconds))
        if (-not $finished) { $item.Process.Kill(); $item.Process.WaitForExit() }
        [ordered]@{ tool = $item.Tool; args = $item.Args; exit = $(if ($finished) { $item.Process.ExitCode } else { $null }); killedAfterMs = $(if ($finished) { $null } else { $limitMs }) }
    }
    $record = [ordered]@{ stage = $Stage.Name; cell = $Cell.Name; warmup = $Cell.Warmup; startedAt = $started.ToUniversalTime().ToString('o')
        finishedAt = (Get-Date).ToUniversalTime().ToString('o'); runs = @($results) }
    # Written last: a cell.json means the cell finished, so a resumed run skips it.
    [IO.File]::WriteAllText((Join-Path $dir 'cell.json'), ($record | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    foreach ($result in $results) {
        if ($null -eq $result.exit) { Write-Warning "    $($result.tool) did not finish in $([math]::Round($limitMs / 60000)) min and was stopped" }
        elseif ($result.exit -eq 2) { throw "    $($result.tool) refused its arguments (exit 2); see $dir" }
        elseif ($result.exit -ne 0) { Write-Warning "    $($result.tool) exit $($result.exit): contract breaches or host anomalies, see $dir" }
    }
    if ((Invoke-Vm 'test -f ~/perf-test/out/DISK_LOW && echo LOW || echo OK').Trim() -eq 'LOW') { throw 'The VM is below its free disk floor (out/DISK_LOW); stopping' }
}

# ------------------------------------------------------------------------------------------------------------------ main
$stages = Get-Stages
$total = ($stages | ForEach-Object { $_.Cells } | ForEach-Object { $_.Seconds } | Measure-Object -Sum).Sum
Write-Host ("{0}: {1} stages, {2} cells, about {3:N1} h of load plus stage changes" -f $Mode, $stages.Count, ($stages | ForEach-Object { $_.Cells.Count } | Measure-Object -Sum).Sum, ($total / 3600))
foreach ($stage in $stages) {
    $minutes = [math]::Ceiling((($stage.Cells | ForEach-Object { $_.Seconds } | Measure-Object -Sum).Sum) / 60)
    Write-Host ("- {0} ({1}, {2} min): {3}" -f $stage.Name, $stage.Test, $minutes, $(if ($stage.Settings.Count -eq 0) { 'live caps' } else { $stage.Settings -join ' ' }))
    if ($Mode -eq 'DryRun') {
        foreach ($cell in $stage.Cells) {
            $what = ($cell.Runs | ForEach-Object { "$($_.Tool) $($_.Args -join ' ')" }) -join '  +  '
            Write-Host ("    {0}{1} ({2} s): {3}" -f $cell.Name, $(if ($cell.Warmup) { ' [warm-up]' } else { '' }), $cell.Seconds, (($what -replace [regex]::Escape($request), '<real day>') -replace [regex]::Escape($points), '<points>'))
        }
    }
}
if ($Mode -eq 'DryRun') { return }

New-Item -ItemType Directory -Force -Path (Join-Path $runDir 'logs'), (Join-Path $runDir 'stages'), (Join-Path $runDir 'vm') | Out-Null
Assert-StudyTokenVariable -Variable $TokenVariable
if (-not (Test-Path $request)) { throw "No recorded day at $request" }
if (-not (Test-Path $points)) {
    & python -I (Join-Path $repo 'infra/vm-perf/build_points.py') --out $points --glob $PointsGlob
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the pool of routable points' }
}
if (-not $Local) {
    $probe = Invoke-Vm 'echo ok' -AllowFailure
    if ($probe.Trim() -ne 'ok') { throw "SSH to $Vm needs a key (BatchMode): ssh-copy-id or add the workstation's key to ~/.ssh/authorized_keys on the VM.`n$probe" }
}
Copy-ToVm
if (-not $Local) {
    # The VM's own certificate (public), so Node trusts the self-signed API address.
    $ca = Join-Path $runDir 'vm/vm-ca.pem'
    & scp -o BatchMode=yes -q "${Vm}:waterflex-scheduler-java/deploy/vm/tls/fullchain.pem" $ca
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the VM certificate' }
    $env:NODE_EXTRA_CA_CERTS = $ca
}
Confirm-Tunnel
# The token's tenant, checked once before hours of calls (prints the tenant ID, never the token).
$who = & node -e "fetch(process.argv[1]+'/api/v1/whoami',{headers:{Authorization:'Bearer '+process.env[process.argv[2]]}}).then(async r=>{console.log(r.status+' '+await r.text());process.exit(r.status===200?0:1)},e=>{console.error(e.cause?.message??e.message);process.exit(1)})" $Url $TokenVariable 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "The token does not work against ${Url}: $who" }
Write-Host "Token works: $($who.Trim())"
Set-Content -Path (Join-Path $runDir 'vm/whoami.txt') -Value $who.Trim()

# What the VM runs before the test: its live caps (no secret is read) and, on the VM, the live check.
Invoke-Vm 'bash ~/perf-test/vm-collect.sh caps' | Set-Content -Path (Join-Path $runDir 'vm/live-caps.txt')
Invoke-Vm 'bash ~/perf-test/vm-collect.sh host' -AllowFailure | Set-Content -Path (Join-Path $runDir 'vm/host.txt')
if (-not $Local -and (Test-Path $CheckScript)) { Invoke-Vm 'bash ~/perf-test/check-vm-live.sh' -AllowFailure | Set-Content -Path (Join-Path $runDir 'vm/check-before.txt') }

$plan = [ordered]@{ mode = $Mode; startedAt = (Get-Date).ToUniversalTime().ToString('o'); url = $Url; apiPath = $(if ($tunnelApi) { "SSH tunnel to $apiTarget" } else { 'direct' }); vm = $(if ($Local) { 'local rehearsal' } else { $Vm })
    metro = $Metro; pointsGlob = $PointsGlob; requestFile = $RequestFile; stages = @($stages | ForEach-Object { [ordered]@{ name = $_.Name; test = $_.Test; settings = $_.Settings; cells = @($_.Cells | ForEach-Object { $_.Name }) } }) }
[IO.File]::WriteAllText((Join-Path $runDir "plan-$Mode.json"), ($plan | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))

Set-KeepAwake $true
$previous = $null
$current = $null
try {
    Invoke-Vm "bash ~/perf-test/vm-arm.sh $DeadmanHours" | Write-Host
    foreach ($stage in $stages) {
        if (Test-StageDone $stage) { Write-Host "Stage $($stage.Name): done already"; continue }
        Write-Host "Stage $($stage.Name) ($(Get-Date -Format HH:mm))"
        Invoke-Vm ("bash ~/perf-test/vm-stage.sh $($stage.Name) " + ($stage.Settings -join ' ')) | Write-Host
        $current = $stage.Name
        # The previous stage's JVMs exited when this stage started them again, so its recordings are complete now.
        if ($null -ne $previous) {
            Copy-FromVm "profiles/$previous" (Join-Path $runDir "stages/$previous/vm-profiles") | Out-Null
            Invoke-Vm "bash ~/perf-test/vm-collect.sh clean $previous" | Out-Null
        }
        foreach ($cell in $stage.Cells) { Invoke-Cell $stage $cell }
        # Logs before the next stage recreates the containers (which drops their logs).
        Invoke-Vm "bash ~/perf-test/vm-collect.sh logs $($stage.Name)" | Out-Null
        Copy-FromVm "out/$($stage.Name)" (Join-Path $runDir "stages/$($stage.Name)/vm-out") | Out-Null
        $previous = $stage.Name
        $current = $null
    }
} finally {
    Write-Host "Restoring the live layout ($(Get-Date -Format HH:mm))"
    try {
        if ($null -ne $current) {
            Invoke-Vm "bash ~/perf-test/vm-collect.sh logs $current" -AllowFailure | Out-Null
            Copy-FromVm "out/$current" (Join-Path $runDir "stages/$current/vm-out") | Out-Null
            $previous = $current
        }
        Invoke-Vm 'bash ~/perf-test/vm-restore.sh workstation' -AllowFailure | Write-Host
        if ($null -ne $previous) {
            Copy-FromVm "profiles/$previous" (Join-Path $runDir "stages/$previous/vm-profiles") | Out-Null
            Invoke-Vm "bash ~/perf-test/vm-collect.sh clean $previous" -AllowFailure | Out-Null
        }
        if (Copy-FromVm 'profiles/routing' (Join-Path $runDir 'vm')) { Invoke-Vm 'bash ~/perf-test/vm-collect.sh clean-routing' -AllowFailure | Out-Null }
        foreach ($file in 'samples.jsonl', 'restore.log', 'deadman.log', 'sampler.err', 'RESTORED') { Copy-FromVm "out/$file" (Join-Path $runDir 'vm') | Out-Null }
        if (-not $Local -and (Test-Path $CheckScript)) { Invoke-Vm 'bash ~/perf-test/check-vm-live.sh' -AllowFailure | Set-Content -Path (Join-Path $runDir 'vm/check-after.txt') }
    } finally {
        if ($null -ne $script:tunnel -and -not $script:tunnel.HasExited) { $script:tunnel.Kill() }
        Set-KeepAwake $false
    }
}

Write-Host 'Summarizing'
& python -I (Join-Path $repo 'infra/vm-perf/summarize.py') --run $runDir
if ($LASTEXITCODE -ne 0) { Write-Warning "The summary failed (exit $LASTEXITCODE); the evidence is in $runDir" }
Write-Host "Done. Summary: $(Join-Path $runDir 'summary.md')"
