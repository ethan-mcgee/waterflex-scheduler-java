param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version)
$ErrorActionPreference = 'Continue' # Native stderr is progress output in Windows PowerShell; check every exit code.
if ($Version -in @('.', '..')) { throw 'Invalid version' }
# Switches nominatim, routing-service and tiles to a prepared, validated map version through docker-compose.map-active.yml,
# and records that in the root .env (ignored by git) so a plain `docker compose up` keeps serving it.
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envFile = Join-Path $root '.env'
$volume = "waterflex-nominatim-preview-$Version"

docker compose --profile map-import run --rm map-import check-activation $Version
if ($LASTEXITCODE -ne 0) { throw "$Version is not ready to activate" }
docker volume inspect $volume | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Nominatim volume $volume does not exist; run prepare-map.ps1 first" }

$lines = @()
if (Test-Path $envFile) { $lines = @(Get-Content $envFile) }
$previous = ($lines | Where-Object { $_ -match '^ACTIVE_MAP_VERSION=' } | Select-Object -First 1) -replace '^ACTIVE_MAP_VERSION=', ''
if (-not $previous) {
  $previous = docker compose --profile map-import run --rm --entrypoint cat map-import /maps/map-version
  if ($LASTEXITCODE -ne 0) { throw 'Could not read the active base map version' }
  $previous = "base Compose files (map-version $previous)"
}

# The preview Nominatim owns the volume the active service is about to use; two PostgreSQL servers must never share it.
$env:PREVIEW_MAP_VERSION = $Version
docker compose -f docker-compose.yml -f docker-compose.map-preview.yml rm -sf nominatim-preview routing-preview tiles-preview
if ($LASTEXITCODE -ne 0) { throw 'Could not remove the preview services' }

if (Test-Path $envFile) { Copy-Item $envFile "$envFile.before-$Version" -Force }
$managed = '^(ACTIVE_MAP_VERSION|COMPOSE_FILE|COMPOSE_PATH_SEPARATOR)='
$kept = @($lines | Where-Object { $_ -notmatch $managed })
$content = ($kept + @("ACTIVE_MAP_VERSION=$Version", 'COMPOSE_FILE=docker-compose.yml;docker-compose.map-active.yml', 'COMPOSE_PATH_SEPARATOR=;')) -join "`n"
[IO.File]::WriteAllText($envFile, "$content`n", [Text.UTF8Encoding]::new($false))
Write-Host "Previous map: $previous"
Write-Host "Rollback: restore $envFile.before-$Version (or delete $envFile if none), then: docker compose up -d --no-deps nominatim routing-service tiles"

$env:ACTIVE_MAP_VERSION = $Version
$env:COMPOSE_FILE = 'docker-compose.yml;docker-compose.map-active.yml'
$env:COMPOSE_PATH_SEPARATOR = ';'
docker compose up -d --no-deps nominatim routing-service tiles
if ($LASTEXITCODE -ne 0) { throw 'Active map services failed to start; see the rollback above' }

$deadline = (Get-Date).AddMinutes(5)
$ready = $false
while (-not $ready -and (Get-Date) -lt $deadline) {
  try {
    $status = Invoke-RestMethod -TimeoutSec 5 'http://localhost:8082/status?format=json'
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://localhost:8001/health' | Out-Null
    $ready = $status.status -eq 0
  } catch { Start-Sleep -Seconds 5 }
}
if (-not $ready) { throw "Nominatim or routing did not become ready within 5 minutes" }
Write-Host "Activated $Version. Nominatim data_updated: $($status.data_updated)"
