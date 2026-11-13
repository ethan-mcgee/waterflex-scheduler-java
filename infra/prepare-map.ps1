# Prepares one map version: the region's extracts (infra/map-import/regions/<Region>.json, default omaha), the depot
# coverage (the portal's depots through DATABASE_URL, or one circle given as -CoverageCircle lat,lng,radiusMi for a
# study region that has no portal depot), the tiles and both road graphs, then the preview services.
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version,
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,59}$')][string]$Region = 'omaha',
    [ValidatePattern('^(-?\d+(\.\d+)?,-?\d+(\.\d+)?,\d+(\.\d+)?)?$')][string]$CoverageCircle = ''
)
$ErrorActionPreference = 'Continue' # Native stderr is progress output in Windows PowerShell; check every exit code.
if ($Version -in @('.', '..')) { throw 'Invalid version' }
$regionFile = Join-Path $PSScriptRoot "map-import/regions/$Region.json"
if (-not (Test-Path -LiteralPath $regionFile)) { throw "No region spec at $regionFile" }
$regionBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($regionFile))
if ($CoverageCircle -ne '') {
    # The same box web/lib/coverage.ts derives from a depot circle, with the 10 mile preparation margin.
    $parts = $CoverageCircle -split ','
    $lat = [double]$parts[0]; $lng = [double]$parts[1]; $radiusMi = [double]$parts[2]
    $angular = ($radiusMi + 10) / 3958.8
    $deltaLat = $angular * 180 / [Math]::PI
    $deltaLng = [Math]::Asin([Math]::Sin($angular) / [Math]::Cos($lat * [Math]::PI / 180)) * 180 / [Math]::PI
    $coverageJson = @{ circles = @(@{ lat = $lat; lng = $lng; radiusMi = $radiusMi }); marginMi = 10
        bounds = @{ south = $lat - $deltaLat; north = $lat + $deltaLat; west = $lng - $deltaLng; east = $lng + $deltaLng } } | ConvertTo-Json -Compress -Depth 4
} else {
    if (-not $env:DATABASE_URL) { throw 'Set DATABASE_URL to read current depot coverage before preparation, or pass -CoverageCircle' }
    $coverageJson = & node --import ./web/node_modules/tsx/dist/loader.mjs ./web/scripts/map-coverage.ts
    if ($LASTEXITCODE -ne 0) { throw 'Could not read current coverage' }
}
$coverage = $coverageJson | ConvertFrom-Json
$bounds = '{0},{1},{2},{3}' -f $coverage.bounds.west, $coverage.bounds.south, $coverage.bounds.east, $coverage.bounds.north

$coverageBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($coverageJson))
docker compose --profile map-import run --rm -e "MAP_COVERAGE_BASE64=$coverageBase64" -e "MAP_REGION_BASE64=$regionBase64" map-import $Version
if ($LASTEXITCODE -ne 0) { throw 'OSM import preparation failed' }

docker compose --profile map-import run --rm tile-import --osm-path="/maps/versions/$Version/omaha.osm.pbf" --output="/maps/versions/$Version/omaha.pmtiles" --bounds=$bounds --download
if ($LASTEXITCODE -ne 0) { throw 'Planetiler generation failed' }

docker compose --profile map-import run --rm -e ROUTING_OSM_FILE="/maps/versions/$Version/omaha.osm.pbf" -e ROUTING_GRAPH_DIR="/maps/versions/$Version/graph" -e ROUTING_MAP_VERSION=$Version routing-prepare
if ($LASTEXITCODE -ne 0) { throw 'GraphHopper import failed' }
# The contraction hierarchy graph for the same map, prepared beside the flexible one (graph-ch-car-v1) so a version
# always carries both; routing-service serves it with ROUTING_CH_ENABLED=true.
docker compose --profile map-import run --rm -e ROUTING_OSM_FILE="/maps/versions/$Version/omaha.osm.pbf" -e ROUTING_GRAPH_DIR="/maps/versions/$Version/graph" -e ROUTING_MAP_VERSION=$Version -e ROUTING_CH_ENABLED=true -e ROUTING_CH_PREPARATION_THREADS=4 routing-prepare
if ($LASTEXITCODE -ne 0) { throw 'GraphHopper CH preparation failed' }
docker compose --profile map-import run --rm map-import finalize $Version
if ($LASTEXITCODE -ne 0) { throw 'Map artifact manifest failed' }

$env:PREVIEW_MAP_VERSION = $Version
docker compose -f docker-compose.yml -f docker-compose.map-preview.yml up -d --no-deps nominatim-preview routing-preview tiles-preview
if ($LASTEXITCODE -ne 0) { throw 'Preview services failed to start' }
Write-Host "Prepared $Version ($Region). Nominatim imports into a separate versioned volume. Validate preview services before cutover."
