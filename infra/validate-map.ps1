# -SkipDepotCoverage leaves out the portal depot check (DATABASE_URL) for a study region without portal depots.
param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version, [switch]$SkipDepotCoverage)
if ($Version -in @('.', '..')) { throw 'Invalid version' }
if (-not $SkipDepotCoverage -and -not $env:DATABASE_URL) { throw 'Set DATABASE_URL for current depot coverage validation, or pass -SkipDepotCoverage' }
$env:PREVIEW_MAP_VERSION = $Version
# validate-map.py calls routing's token-protected endpoints; use the same local-only default as Compose when unset.
if (-not $env:ROUTING_AUTH_TOKEN) { $env:ROUTING_AUTH_TOKEN = 'local-compose-routing-token-not-for-production' }
docker compose --profile map-import run --rm map-import verify $Version
if ($LASTEXITCODE -ne 0) { throw 'Prepared artifact checksums failed' }
# Nominatim imports the clipped, merged extract, so its data date is that file's newest object, not the newest object
# anywhere in a state download (an edit outside the coverage circle is clipped away).
$fileinfo = docker compose --profile map-import run --rm --entrypoint osmium map-import fileinfo -e -g data.timestamp.last "/maps/versions/$Version/omaha.osm.pbf"
if ($LASTEXITCODE -ne 0) { throw 'Could not read the merged extract timestamp' }
# Compose may rebuild the image first and, without a terminal, print BuildKit progress on stdout; keep only the timestamp.
$mergedTimestamp = @($fileinfo | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$' })
if ($mergedTimestamp.Count -ne 1) { throw 'The merged extract has no single data timestamp' }
$mergedTimestamp = $mergedTimestamp[0]
$previewId = docker compose -f docker-compose.yml -f docker-compose.map-preview.yml ps -q routing-preview
if ($LASTEXITCODE -ne 0 -or -not $previewId) { throw 'Routing preview is not running' }
$directory = Join-Path $PSScriptRoot "../.scratch/map-$Version"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$manifest = Join-Path $directory 'manifest.json'
docker cp "${previewId}:/maps/versions/$Version/manifest.json" $manifest
if ($LASTEXITCODE -ne 0) { throw 'Could not read prepared manifest' }
if (-not $SkipDepotCoverage) {
    node --import ./web/node_modules/tsx/dist/loader.mjs ./web/scripts/map-coverage.ts $manifest
    if ($LASTEXITCODE -ne 0) { throw 'Current depot coverage exceeds prepared maps' }
}
$validation = python "$PSScriptRoot/validate-map.py" $manifest $mergedTimestamp
if ($LASTEXITCODE -ne 0) { throw 'Preview map validation failed; activation remains blocked' }
$report = Join-Path $directory 'validated.json'
[IO.File]::WriteAllText($report, ($validation -join "`n"), [Text.UTF8Encoding]::new($false))
# The preview services mount the map volume read-only; map-import is the one service that writes it.
$reportDirectory = (Resolve-Path $directory).Path
docker compose --profile map-import run --rm -v "${reportDirectory}:/report:ro" --entrypoint cp map-import /report/validated.json "/maps/versions/$Version/validated.json"
if ($LASTEXITCODE -ne 0) { throw 'Could not save validation report' }
Write-Host "Validated $Version. Active maps have not been changed. Review $report before cutover."
