import type * as maplibregl from "maplibre-gl";
import type { FeatureCollection } from "geojson";
import type { GeometryResponse, RoadFeature } from "@/lib/dispatchGeometry";

/** The style of one plan's routes: who is drawn, in what color, and how strongly. */
export type RouteStyle = {
  visible: boolean;
  /** Gray dashed lines, for the current plan under the optimized one. */
  ghost: boolean;
  /** The technicians to draw; null draws everyone in the geometry. */
  only: ReadonlySet<string> | null;
  colorByTech: ReadonlyMap<string, string>;
  focusTechnicianId: string | null;
  dimmed: boolean;
};

const GHOST_COLOR = "#69766f";
const layerId = (prefix: string, technicianId: string) => `route-${prefix}-${technicianId}`;

function opacity(style: RouteStyle, technicianId: string): number {
  if (style.focusTechnicianId !== null && style.focusTechnicianId !== technicianId) return 0.15;
  return style.dimmed ? 0.45 : 1;
}

/**
 * Brings one plan's route layers in line with its geometry and style. Each technician keeps a stable source and layer
 * (`route-<prefix>-<technician>`), so a change of view or focus repaints the lines instead of rebuilding them, and a
 * technician no longer in the geometry loses theirs. Current-plan layers stay under optimized-plan layers.
 */
export function syncRouteLayers(map: maplibregl.Map, prefix: "current" | "proposal", geometry: GeometryResponse | null, style: RouteStyle): void {
  const byTech = new Map<string, RoadFeature[]>();
  for (const feature of geometry?.features ?? []) {
    const list = byTech.get(feature.properties.technicianId);
    if (list) list.push(feature); else byTech.set(feature.properties.technicianId, [feature]);
  }
  const wanted = new Set([...byTech.keys()].map(id => layerId(prefix, id)));
  for (const layer of map.getStyle().layers) {
    if (layer.id.startsWith(`route-${prefix}-`) && !wanted.has(layer.id)) {
      map.removeLayer(layer.id);
      if (map.getSource(layer.id)) map.removeSource(layer.id);
    }
  }
  const firstProposal = prefix === "current" ? map.getStyle().layers.find(layer => layer.id.startsWith("route-proposal-"))?.id : undefined;
  for (const [technicianId, features] of byTech) {
    const id = layerId(prefix, technicianId);
    const data: FeatureCollection = { type: "FeatureCollection", features };
    const source = map.getSource<maplibregl.GeoJSONSource>(id);
    if (source === undefined) {
      map.addSource(id, { type: "geojson", data });
      map.addLayer({ id, source: id, type: "line", layout: { "line-join": "round", "line-cap": "round" }, paint: {} }, firstProposal);
    } else source.setData(data);
    const shown = style.visible && (style.only === null || style.only.has(technicianId));
    map.setLayoutProperty(id, "visibility", shown ? "visible" : "none");
    map.setPaintProperty(id, "line-color", style.ghost ? GHOST_COLOR : style.colorByTech.get(technicianId) ?? GHOST_COLOR);
    map.setPaintProperty(id, "line-width", style.ghost ? 2 : 3.5);
    map.setPaintProperty(id, "line-dasharray", style.ghost ? [2, 1.5] : undefined); // undefined restores the default: a solid line.
    map.setPaintProperty(id, "line-opacity", opacity(style, technicianId));
  }
}
