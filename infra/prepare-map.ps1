param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version)
$ErrorActionPreference = 'Continue' # Native stderr is progress output in Windows PowerShell; check every exit code.
if ($Version -in @('.', '..')) { throw 'Invalid version' }
if (-not $env:DATABASE_URL) { throw 'Set DATABASE_URL to read current depot coverage before preparation' }
$coverageJson = & node --import ./web/node_modules/tsx/dist/loader.mjs ./web/scripts/map-coverage.ts
if ($LASTEXITCODE -ne 0) { throw 'Could not read current coverage' }
$coverage = $coverageJson | ConvertFrom-Json
$bounds = '{0},{1},{2},{3}' -f $coverage.bounds.west, $coverage.bounds.south, $coverage.bounds.east, $coverage.bounds.north

$coverageBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($coverageJson))
docker compose --profile map-import run --rm -e "MAP_COVERAGE_BASE64=$coverageBase64" map-import $Version
if ($LASTEXITCODE -ne 0) { throw 'OSM import preparation failed' }

docker compose --profile map-import run --rm tile-import --osm-path="/maps/versions/$Version/omaha.osm.pbf" --output="/maps/versions/$Version/omaha.pmtiles" --bounds=$bounds --download
if ($LASTEXITCODE -ne 0) { throw 'Planetiler generation failed' }

docker compose run --rm -e ROUTING_OSM_FILE="/maps/versions/$Version/omaha.osm.pbf" -e ROUTING_GRAPH_DIR="/maps/versions/$Version/graph" -e ROUTING_MAP_VERSION=$Version routing-service --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { throw 'GraphHopper import failed' }
docker compose --profile map-import run --rm map-import finalize $Version
if ($LASTEXITCODE -ne 0) { throw 'Map artifact manifest failed' }

$env:PREVIEW_MAP_VERSION = $Version
docker compose -f docker-compose.yml -f docker-compose.map-preview.yml up -d --no-deps nominatim-preview routing-preview tiles-preview
if ($LASTEXITCODE -ne 0) { throw 'Preview services failed to start' }
Write-Host "Prepared $Version. Nominatim imports into a separate versioned volume. Validate preview services before cutover."
