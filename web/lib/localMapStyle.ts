import type { StyleSpecification } from "maplibre-gl";

export const localMapStyle: StyleSpecification = {
  version: 8,
  sources: {
    omaha: {
      type: "vector",
      tiles: ["http://localhost:8083/omaha/{z}/{x}/{y}.mvt"],
      minzoom: 0,
      maxzoom: 14,
      attribution: "© OpenMapTiles © OpenStreetMap contributors",
    },
  },
  layers: [
    { id: "background", type: "background", paint: { "background-color": "#edf2ed" } },
    { id: "parks", type: "fill", source: "omaha", "source-layer": "park", paint: { "fill-color": "#cfe7ce" } },
    { id: "water", type: "fill", source: "omaha", "source-layer": "water", paint: { "fill-color": "#a5d8ec" } },
    { id: "roads", type: "line", source: "omaha", "source-layer": "transportation", paint: { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.5, 14, 2.5] } },
  ],
};
