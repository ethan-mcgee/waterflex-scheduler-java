# Deploys to the scheduler VM from this workstation (docs/operations-runbook.md, "Releases"; deploy/vm/README.md,
# "Upgrade"). GitHub cannot reach the VM (the VPN passes only SSH from here), so every deploy starts here. The images
# are the ones .github/workflows/images.yml built and pushed to GHCR; nothing is built here or on the VM.
#
#   infra/deploy-vm.ps1 -Release v2026.12.01.1   the release, through infra/release-vm.sh on the VM; an earlier tag rolls back
#   infra/deploy-vm.ps1 -Test <commit>           the live stack on a pushed commit under test, in an agreed window
#   infra/deploy-vm.ps1 -Restore                 the live stack back on its release after -Test
#
#   -Ship            the workstation pulls the images and loads them into the VM over SSH (a VM that cannot reach ghcr.io)
#   -AllowMigration  with -Test: a commit whose migrations differ from the release's. The database is backed up first and
#                    goes back to that dump on -Restore (or the dead-man timer's restore). With -Release: a rollback to
#                    a release that lacks migrations the live one applied (docs/operations-runbook.md, "Start, stop and
#                    upgrade", says what that needs).
#   -DryRun          checks the commit and the images, then prints what it would do on the VM, and changes nothing
#
# SSH uses a key (BatchMode). Each run's commands and output go to .scratch/deploy-vm/<time>.log. No token is used.
[CmdletBinding(DefaultParameterSetName = 'Release')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Release')][ValidatePattern('^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$')][string]$Release,
    [Parameter(Mandatory, ParameterSetName = 'Test')][ValidatePattern('^[A-Za-z0-9._/-]+$')][string]$Test,
    [Parameter(Mandatory, ParameterSetName = 'Restore')][switch]$Restore,
    [ValidatePattern('^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$')][string]$Vm = 'ethan@192.168.20.171',
    [switch]$Ship,
    [switch]$AllowMigration,
    # With -Test: when the VM puts its live release back by itself if -Restore never runs (infra/vm-perf/vm-arm.sh).
    [ValidateRange(1, 24)][int]$DeadmanHours = 4,
    [ValidatePattern('^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._-]+)*$')][string]$Registry = 'ghcr.io/ethan-mcgee',
    # How long to wait for images.yml when an image is not in GHCR yet.
    [ValidateRange(0, 120)][int]$WaitForImagesMinutes = 45,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Import-Module (Join-Path $PSScriptRoot 'vm-ssh.psm1') -Force
$migrations = 'scheduler-service/src/main/resources/db/migration'
$checkout = '~/waterflex-scheduler-java'
$logDir = Join-Path $repo '.scratch/deploy-vm'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ('{0}.log' -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
$gitBash = Get-GitBash

function Write-Step([string]$Text) { Write-Host "== $Text"; Add-Content -Path $log -Value "== $Text" }

# Runs git in this repository; returns its trimmed output, or throws unless -AllowFailure (then $null on failure).
function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $ErrorActionPreference = 'Continue'
    $output = & git -C $repo @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) {
        if ($AllowFailure) { return $null }
        throw "git $($Arguments -join ' ') failed with exit $code`n$output"
    }
    $output.Trim()
}

# A step on the VM: printed and skipped by -DryRun, otherwise run, and a failure stops the deploy.
function Invoke-VmStep([string]$Command, [string]$What) {
    if ($DryRun) { Write-Host "   would run on the VM: $Command"; return '' }
    $result = Invoke-VmCommand -Vm $Vm -Command $Command -Log $log
    Write-Host $result.Output.TrimEnd()
    if ($result.ExitCode -ne 0) { throw "$What failed on the VM (exit $($result.ExitCode)); the log is $log" }
    $result.Output
}

# A read on the VM, which -DryRun runs too: it changes nothing.
function Read-Vm([string]$Command) {
    $result = Invoke-VmCommand -Vm $Vm -Command $Command -Log $log
    if ($result.ExitCode -ne 0) { throw "Could not read from the VM: $Command`n$($result.Output)" }
    $result.Output.Trim()
}

# The two image tags of a commit, from infra/image-revisions.sh (the names release-vm.sh and images.yml use).
function Get-ImageRevision([string]$Ref) {
    $ErrorActionPreference = 'Continue'
    $output = & $gitBash (Join-Path $repo 'infra/image-revisions.sh').Replace('\', '/') $Ref 2>&1 | ForEach-Object { "$_" } | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $match = [regex]::Match($output.Trim(), '^([0-9a-f]{12}) ([0-9a-f]{12})$')
    if ($code -ne 0 -or -not $match.Success) { throw "Could not name the images of $Ref`n$output" }
    [pscustomobject]@{
        Engine  = $match.Groups[1].Value
        Routing = $match.Groups[2].Value
        Images  = @("waterflex-scheduler-engine:$($match.Groups[1].Value)", "waterflex-scheduler-routing:$($match.Groups[2].Value)")
    }
}

function Test-RegistryImage([string]$Image) {
    $ErrorActionPreference = 'Continue'
    & docker manifest inspect "$Registry/$Image" *> $null
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $code -eq 0
}

# Both images must be in GHCR. A missing one is built by dispatching images.yml for the commit, then waited for.
function Confirm-RegistryImage([string]$Commit, $Revisions) {
    $missing = @($Revisions.Images | Where-Object { -not (Test-RegistryImage $_) })
    if ($missing.Count -eq 0) { Write-Step "Both images are in $Registry"; return }
    if ($DryRun) { Write-Host "   would build $($missing -join ', ') with: gh workflow run images.yml -f ref=$Commit"; return }
    Write-Step "Not in $Registry yet: $($missing -join ', '); running images.yml for $Commit"
    $ErrorActionPreference = 'Continue'
    & gh workflow run images.yml --repo ethan-mcgee/waterflex-scheduler-java -f "ref=$Commit" 2>&1 | Add-Content -Path $log
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Could not start images.yml (gh exit $code); the log is $log" }
    $deadline = (Get-Date).AddMinutes($WaitForImagesMinutes)
    while ($true) {
        $missing = @($missing | Where-Object { -not (Test-RegistryImage $_) })
        if ($missing.Count -eq 0) { Write-Step "Both images are in $Registry"; return }
        if ((Get-Date) -gt $deadline) {
            throw "After $WaitForImagesMinutes minutes $($missing -join ', ') is still not in $Registry. Check the images.yml run (gh run list --workflow images.yml); if it succeeded, the GHCR package may be private (set it public in its package settings)."
        }
        Start-Sleep -Seconds 30
    }
}

# The release the VM's .env records (what its live layout runs), or '' before the first release.
function Get-LiveRelease {
    $release = Read-Vm "sed -n 's/^SCHEDULER_RELEASE=\([^[:space:]]*\).*/\1/p' $checkout/deploy/vm/.env | tail -n 1"
    if ($release -ne '' -and $release -notmatch '^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$') { throw "The VM's .env records '$release', not a release tag" }
    $release
}

# Migrations the target does not have, or has changed, compared with the live release: what a rollback cannot undo.
function Get-MigrationLoss([string]$Live, [string]$Target) {
    $lost = Invoke-Git -Arguments @('diff', '--name-only', '--diff-filter=DM', $Live, $Target, '--', $migrations)
    @($lost -split "`r?`n" | Where-Object { $_ -ne '' })
}

function Invoke-Ship([string]$Ref) {
    if ($DryRun) { Write-Host "   would ship the images: bash infra/ship-images.sh $Ref $Vm"; return }
    Write-Step "Shipping the images of $Ref through this workstation"
    $env:SCHEDULER_IMAGE_REGISTRY = $Registry
    $ErrorActionPreference = 'Continue'
    & $gitBash (Join-Path $repo 'infra/ship-images.sh').Replace('\', '/') $Ref $Vm 2>&1 | Tee-Object -FilePath $log -Append | Write-Host
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Shipping the images failed (exit $code); the log is $log" }
}

# The read-only live check (infra/check-vm-live.sh) from this checkout, so a release older than the script is checked too.
function Invoke-LiveCheck([string]$ExpectedRevision) {
    if ($DryRun) { Write-Host '   would run infra/check-vm-live.sh on the VM'; return }
    Copy-ToVmFolder -Vm $Vm -Path @((Join-Path $repo 'infra/check-vm-live.sh')) -Folder 'perf-test' -Log $log
    $result = Invoke-VmCommand -Vm $Vm -Command 'bash ~/perf-test/check-vm-live.sh' -Log $log
    Write-Host $result.Output.TrimEnd()
    if ($result.ExitCode -ne 0) { throw "The live check failed after the deploy; the log is $log" }
    if ($ExpectedRevision -ne '' -and $result.Output -notmatch "PASS /health runs commit $ExpectedRevision") {
        throw "The VM does not serve commit $ExpectedRevision; the log is $log"
    }
}

Write-Step "Log: $log"
Invoke-Git -Arguments @('fetch', '--quiet', '--tags', 'origin', 'main') | Out-Null

if ($PSCmdlet.ParameterSetName -eq 'Release') {
    # The same checks release-vm.sh makes on the VM, made here first so a bad tag stops before anything moves.
    if ((Invoke-Git -Arguments @('cat-file', '-t', "refs/tags/$Release") -AllowFailure) -ne 'tag') { throw "$Release is not an annotated tag on origin" }
    $published = (Invoke-Git -Arguments @('ls-remote', 'origin', "refs/tags/$Release")) -split '\s+' | Select-Object -First 1
    if ($published -ne (Invoke-Git -Arguments @('rev-parse', "refs/tags/$Release"))) { throw "$Release here differs from origin's" }
    $commit = Invoke-Git -Arguments @('rev-parse', "$Release^{commit}")
    if ($null -eq (Invoke-Git -Arguments @('merge-base', '--is-ancestor', $commit, 'origin/main') -AllowFailure)) { throw "$Release is not on origin/main; release merged commits only" }
    $revisions = Get-ImageRevision $Release
    Write-Step "$Release is $($revisions.Images -join ' and ')"
    Confirm-RegistryImage $commit $revisions

    $live = Get-LiveRelease
    Write-Step "The VM's live release: $(if ($live -eq '') { 'none' } else { $live })"
    if ($live -ne '' -and $live -ne $Release) {
        $lost = Get-MigrationLoss $live $Release
        if ($lost.Count -gt 0 -and -not $AllowMigration) {
            throw "$Release lacks or changes migrations $live applied ($($lost -join ', ')); a rollback past a migration needs the runbook's steps. Add -AllowMigration once they are done."
        }
    }
    if ($Ship) { Invoke-Ship $Release }
    # The VM checks out the tag first, so the tag's own release-vm.sh runs (including a rollback's older one).
    Write-Step "Releasing $Release on the VM"
    Invoke-VmStep ("cd $checkout && git fetch --quiet --tags origin main && git -c advice.detachedHead=false checkout --quiet --detach $Release && " +
        "SCHEDULER_IMAGE_REGISTRY=$Registry bash infra/release-vm.sh $Release") 'The release' | Out-Null
    Invoke-LiveCheck $revisions.Engine
    Write-Step "$Release is live ($($revisions.Engine), routing $($revisions.Routing))$(if ($DryRun) { ' (dry run: nothing changed)' })"
    return
}

# Test and Restore use the VM performance test's scripts in ~/perf-test: the stage start with pinned images, the dead-man
# timer and the restore of the live layout (infra/vm-perf/README.md).
$perfScripts = @(Get-ChildItem (Join-Path $repo 'infra/vm-perf') -File | Where-Object { $_.Extension -in '.sh', '.yml' } | ForEach-Object { $_.FullName })

if ($PSCmdlet.ParameterSetName -eq 'Test') {
    $commit = Invoke-Git -Arguments @('rev-parse', '--verify', "$Test^{commit}") -AllowFailure
    if ($null -eq $commit) { throw "No commit '$Test' here; fetch or push it first" }
    # images.yml checks the commit out on GitHub, so it must be pushed.
    if ((Invoke-Git -Arguments @('branch', '-r', '--contains', $commit)) -eq '') { throw "$commit is on no branch of origin; push it first" }
    $revisions = Get-ImageRevision $commit
    Write-Step "$Test is $($revisions.Images -join ' and ')"
    Confirm-RegistryImage $commit $revisions

    $live = Get-LiveRelease
    if ($live -eq '') { throw 'The VM records no release; release one before testing on it' }
    Write-Step "The VM's live release: $live; -Restore (or the dead-man timer after $DeadmanHours h) returns to it"
    $migrationsDiffer = $null -eq (Invoke-Git -Arguments @('diff', '--quiet', $live, $commit, '--', $migrations) -AllowFailure)
    if ($migrationsDiffer -and -not $AllowMigration) {
        throw "$Test's migrations differ from ${live}'s, and scheduler-admin would apply them to the production database. Add -AllowMigration to back the database up first and restore that dump on -Restore."
    }
    if ($Ship) { Invoke-Ship $commit }
    if (-not $DryRun) { Copy-ToVmFolder -Vm $Vm -Path $perfScripts -Folder 'perf-test' -Log $log }
    # Armed before anything changes, so a lost workstation still gets the live release back.
    Write-Step "Arming the dead-man timer ($DeadmanHours h)"
    Invoke-VmStep "bash ~/perf-test/vm-arm.sh $DeadmanHours" 'Arming the dead-man timer' | Out-Null
    if ($migrationsDiffer) {
        Write-Step 'Backing up the database before the test migrates it'
        $backup = Invoke-VmStep "cd $checkout && bash infra/backup-vm.sh" 'The backup'
        if (-not $DryRun) {
            $dump = [regex]::Match($backup, 'backup (/\S+\.dump) ').Groups[1].Value
            if ($dump -eq '') { throw "The backup printed no dump path; nothing was deployed. Run infra/deploy-vm.ps1 -Restore to disarm the timer.`n$backup" }
            # vm-restore.sh restores this dump before it starts the live layout.
            Invoke-VmStep "printf '%s\n' $dump > ~/perf-test/restore-database.dump" 'Recording the dump' | Out-Null
        }
    }
    $stage = "test-$($revisions.Engine)"
    Write-Step "Starting the live stack on $($revisions.Engine) (stage $stage)"
    try {
        Invoke-VmStep ("SCHEDULER_IMAGE_REGISTRY=$Registry bash ~/perf-test/vm-stage.sh $stage " +
            "SCHEDULER_REVISION=$($revisions.Engine) ROUTING_REVISION=$($revisions.Routing)") 'The test stage' | Out-Null
    } catch {
        Write-Warning 'The test stage did not start; putting the live release back'
        Invoke-VmStep 'bash ~/perf-test/vm-restore.sh deploy-vm-failed-test' 'The restore' | Out-Null
        throw
    }
    if (-not $DryRun) {
        $health = Read-Vm 'curl --silent --max-time 15 http://127.0.0.1:8000/health' | ConvertFrom-Json
        if ($null -eq $health -or -not ($health.PSObject.Properties.Name -contains 'revision') -or $health.revision -ne $revisions.Engine) {
            throw "The VM's /health does not report revision $($revisions.Engine); run infra/deploy-vm.ps1 -Restore"
        }
    }
    Write-Step "The live stack runs $($revisions.Engine). Put the release back with: infra/deploy-vm.ps1 -Restore$(if ($DryRun) { ' (dry run: nothing changed)' })"
    return
}

# Restore
$live = Get-LiveRelease
Write-Step "Putting the live release $live back"
if (-not $DryRun) { Copy-ToVmFolder -Vm $Vm -Path $perfScripts -Folder 'perf-test' -Log $log }
Invoke-VmStep 'bash ~/perf-test/vm-restore.sh deploy-vm' 'The restore' | Out-Null
$revision = Read-Vm "sed -n 's/^SCHEDULER_REVISION=\([^[:space:]]*\).*/\1/p' $checkout/deploy/vm/.env | tail -n 1"
Invoke-LiveCheck $revision
Write-Step "$live is live again$(if ($DryRun) { ' (dry run: nothing changed)' })"
