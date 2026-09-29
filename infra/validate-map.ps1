param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version)
if ($Version -in @('.', '..')) { throw 'Invalid version' }
if (-not $env:DATABASE_URL) { throw 'Set DATABASE_URL for current depot coverage validation' }
$env:PREVIEW_MAP_VERSION = $Version
docker compose --profile map-import run --rm map-import verify $Version
if ($LASTEXITCODE -ne 0) { throw 'Prepared artifact checksums failed' }
$previewId = docker compose -f docker-compose.yml -f docker-compose.map-preview.yml ps -q routing-preview
if ($LASTEXITCODE -ne 0 -or -not $previewId) { throw 'Routing preview is not running' }
$directory = Join-Path $PSScriptRoot "../.scratch/map-$Version"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$manifest = Join-Path $directory 'manifest.json'
docker cp "${previewId}:/maps/versions/$Version/manifest.json" $manifest
if ($LASTEXITCODE -ne 0) { throw 'Could not read prepared manifest' }
node --import ./web/node_modules/tsx/dist/loader.mjs ./web/scripts/map-coverage.ts $manifest
if ($LASTEXITCODE -ne 0) { throw 'Current depot coverage exceeds prepared maps' }
$validation = python "$PSScriptRoot/validate-map.py" $manifest
if ($LASTEXITCODE -ne 0) { throw 'Preview map validation failed; activation remains blocked' }
$report = Join-Path $directory 'validated.json'
[IO.File]::WriteAllText($report, ($validation -join "`n"), [Text.UTF8Encoding]::new($false))
docker cp $report "${previewId}:/maps/versions/$Version/validated.json"
if ($LASTEXITCODE -ne 0) { throw 'Could not save validation report' }
Write-Host "Validated $Version. Active maps have not been changed. Review $report before cutover."
