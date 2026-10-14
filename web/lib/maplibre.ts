import * as maplibregl from "maplibre-gl";

// maplibre-gl 6 derives its worker's URL from its own import.meta.url, which a bundled module does not carry, so the
// worker fails to load. scripts/copy-maplibre-worker.ts copies the installed version's worker into public/ before
// `next dev` and `next build`; maplibre loads it from there. Map components import maplibre from this module so the
// URL is set before any map is created.
maplibregl.setWorkerUrl(`/maplibre/maplibre-gl-worker-${maplibregl.getVersion()}.mjs`);

export { maplibregl };
