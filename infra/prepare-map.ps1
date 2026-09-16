param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Version)
$ErrorActionPreference = 'Stop'

docker compose --profile map-import run --rm map-import $Version
if ($LASTEXITCODE -ne 0) { throw 'OSM import preparation failed' }

docker compose --profile map-import run --rm tile-import --osm-path="/maps/versions/$Version/omaha.osm.pbf" --output="/maps/versions/$Version/omaha.pmtiles" --bounds=-97.5,40.5,-95.2,42.1 --download
if ($LASTEXITCODE -ne 0) { throw 'Planetiler generation failed' }

docker compose run --rm -e ROUTING_OSM_FILE="/maps/versions/$Version/omaha.osm.pbf" -e ROUTING_GRAPH_DIR="/maps/versions/$Version/graph" -e ROUTING_MAP_VERSION=$Version routing-service --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { throw 'GraphHopper import failed' }

Write-Host "Prepared $Version. Stop booking and import Nominatim from /maps/versions/$Version/omaha.osm.pbf before activating."
