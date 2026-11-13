# Tokens for study scripts, which never write a token to a file (owner's rule). A script issues each token through
# scheduler-admin into an environment variable of its own process; the tools it starts inherit that environment and read
# the token with --token-env <variable> (the kit, load.ts, quality.ts) or SCHEDULER_BACKTEST_TOKEN (backtest_replay.py).
# The script revokes every token it issued when it ends, so a token lives only as long as its run.

# Issues a token for the tenant and puts it in env:<Variable>. Returns the token's ID (not a secret), which revokes it.
# Compose is the docker argument list up to the subcommand, for example @('compose', '-f', <file>, '--env-file', <file>).
function New-StudyToken {
    param(
        [Parameter(Mandatory)][string[]]$Compose,
        [Parameter(Mandatory)][string]$Tenant,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')][string]$Variable
    )
    # Docker writes progress to stderr, which must not stop the script.
    $ErrorActionPreference = 'Continue'
    $output = & docker @Compose run --rm scheduler-admin --command=issue-token $Tenant $Name $Label 2>&1 | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $id = [regex]::Match($output, '(?m)^id: (\S+)').Groups[1].Value
    $value = [regex]::Match($output, '(?m)^token: (wfs_\S+)').Groups[1].Value
    if ($code -ne 0 -or $id -eq '' -or $value -eq '') {
        throw "Could not issue a token for $Tenant`n$($output -replace '(?m)^token: \S+', 'token: <withheld>')" }
    Set-Item -Path "env:$Variable" -Value $value
    $id
}

# Revokes a token this run issued and clears its variable. Errors are logged, never thrown: it runs in finally blocks,
# where the run's own failure is the one to report. A token left active is named in the log and the console.
function Remove-StudyToken {
    param(
        [Parameter(Mandatory)][string[]]$Compose,
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Variable,
        [Parameter(Mandatory)][string]$Log
    )
    Remove-Item -Path "env:$Variable" -ErrorAction SilentlyContinue
    $ErrorActionPreference = 'Continue'
    & docker @Compose run --rm scheduler-admin --command=revoke-token $Id *>> $Log
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { Write-Warning "Token $Id was not revoked (see $Log); revoke it with scheduler-admin --command=revoke-token $Id" }
}

# The token a caller supplies for a study on a stack the script does not manage, by variable name, checked before the
# run starts rather than as a 401 an hour in.
function Assert-StudyTokenVariable {
    param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')][string]$Variable)
    $value = [Environment]::GetEnvironmentVariable($Variable)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Set `$env:$Variable to the study's token first (docs/operations-runbook.md, Tokens); it is never read from a file" }
}

Export-ModuleMember -Function New-StudyToken, Remove-StudyToken, Assert-StudyTokenVariable
