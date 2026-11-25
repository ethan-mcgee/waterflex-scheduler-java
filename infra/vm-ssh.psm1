# The scheduler VM over SSH, for workstation scripts (infra/deploy-vm.ps1, infra/vm-perf/run-vm-performance.ps1). Key
# authentication only (BatchMode): an unattended step cannot answer a password prompt. Commands reach the VM's shell as
# one string, so they use single quotes only: Windows PowerShell 5.1 mangles double quotes inside native arguments.

# Runs a command on the VM and returns its output and exit code. Each command and its output are appended to Log.
function Invoke-VmCommand {
    param(
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$')][string]$Vm,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string]$Log
    )
    if ($Command.Contains('"')) { throw "A VM command uses single quotes only: $Command" }
    Add-Content -Path $Log -Value "`n# $(Get-Date -Format o) ssh $Vm $Command"
    # ssh writes warnings to stderr, which must not stop the caller.
    $ErrorActionPreference = 'Continue'
    $output = & ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=30 $Vm $Command 2>&1 | ForEach-Object { "$_" } | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Add-Content -Path $Log -Value $output
    [pscustomobject]@{ Output = $output; ExitCode = $code }
}

# Copies local files into a folder under the VM user's home, creating it.
function Copy-ToVmFolder {
    param(
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$')][string]$Vm,
        [Parameter(Mandatory)][string[]]$Path,
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Folder,
        [Parameter(Mandatory)][string]$Log
    )
    $made = Invoke-VmCommand -Vm $Vm -Command "mkdir -p ~/$Folder" -Log $Log
    if ($made.ExitCode -ne 0) { throw "Could not make ~/$Folder on the VM`n$($made.Output)" }
    $ErrorActionPreference = 'Continue'
    & scp -o BatchMode=yes -q @Path "${Vm}:$Folder/" 2>&1 | Add-Content -Path $Log
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Could not copy $($Path -join ', ') to ~/$Folder on the VM (scp exit $code)" }
}

# Git Bash, which the binary pipes of infra/ship-images.sh need. PowerShell's plain `bash` is WSL, which has no Docker here.
function Get-GitBash {
    $path = 'C:\Program Files\Git\bin\bash.exe'
    if (-not (Test-Path $path)) { throw "Git Bash is not at $path" }
    $path
}

Export-ModuleMember -Function Invoke-VmCommand, Copy-ToVmFolder, Get-GitBash
