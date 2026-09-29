"use client";

import { useEffect, useRef } from "react";
import maplibregl from "maplibre-gl";
import { z } from "zod";
import { bookingLocationResponse } from "@/lib/contracts";
import { localMapStyle } from "@/lib/localMapStyle";

export type ServiceArea = z.infer<typeof bookingLocationResponse>["serviceArea"];
export type PinCandidate = z.infer<typeof bookingLocationResponse>["candidates"][number];
type Point = { lat: number; lng: number };
const tileMetadata = z.object({ bounds: z.tuple([z.number().finite(), z.number().finite(), z.number().finite(), z.number().finite()])
  .refine(b => b[0] < b[2] && b[1] < b[3] && b[0] >= -180 && b[2] <= 180 && b[1] >= -90 && b[3] <= 90) });

export default function AddressPinMap({ candidates, area, pin, onPlace, onAvailableChange }: {
  candidates: PinCandidate[]; area: ServiceArea; pin: Point | null;
  onPlace: (point: Point) => void; onAvailableChange: (available: boolean) => void;
}) {
  const element = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const callbacks = useRef({ onPlace, onAvailableChange });
  callbacks.current = { onPlace, onAvailableChange };
  const pinRef = useRef(pin); pinRef.current = pin;
  useEffect(() => {
    const controller = new AbortController();
    let map: maplibregl.Map | null = null;
    callbacks.current.onAvailableChange(false);
    async function initialize() {
      try {
        const res = await fetch("http://localhost:8083/omaha.json", { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]) });
        if (!res.ok) throw new Error("Map unavailable");
        const metadata = tileMetadata.parse(await res.json());
        if (controller.signal.aborted || !element.current) return;
        map = new maplibregl.Map({ container: element.current, style: localMapStyle,
          bounds: [[area.bounds.west, area.bounds.south], [area.bounds.east, area.bounds.north]], fitBoundsOptions: { padding: 20 } });
        mapRef.current = map;
        let failed = false;
        map.on("error", () => { failed = true; callbacks.current.onAvailableChange(false); });
        map.on("idle", () => {
          if (!map || failed) return;
          const p = pinRef.current;
          const inside = !p || (p.lng >= metadata.bounds[0] && p.lng <= metadata.bounds[2] && p.lat >= metadata.bounds[1] && p.lat <= metadata.bounds[3]);
          callbacks.current.onAvailableChange(inside && map.areTilesLoaded());
        });
        map.on("movestart", () => callbacks.current.onAvailableChange(false));
        map.addControl(new maplibregl.NavigationControl());
        map.on("click", event => callbacks.current.onPlace({ lat: event.lngLat.lat, lng: event.lngLat.lng }));
        map.on("load", () => {
          if (!map) return;
          // Draw actual depot circles, not just their bounding rectangle.
          const features = area.circles.map(circle => {
            const angular = circle.radiusMi / 3958.8, latitude = circle.lat * Math.PI / 180, longitude = circle.lng * Math.PI / 180;
            const coordinates = Array.from({ length: 97 }, (_, index) => {
              const bearing = index * 2 * Math.PI / 96;
              const lat = Math.asin(Math.sin(latitude) * Math.cos(angular) + Math.cos(latitude) * Math.sin(angular) * Math.cos(bearing));
              const lng = longitude + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(latitude), Math.cos(angular) - Math.sin(latitude) * Math.sin(lat));
              return [lng * 180 / Math.PI, lat * 180 / Math.PI];
            });
            return { type: "Feature" as const, properties: {}, geometry: { type: "Polygon" as const, coordinates: [coordinates] } };
          });
          map.addSource("coverage", { type: "geojson", data: { type: "FeatureCollection", features } });
          map.addLayer({ id: "coverage", type: "line", source: "coverage", paint: { "line-color": "#1558a6", "line-width": 2 } });
          const initial = pinRef.current;
          if (initial) {
            const candidate = candidates.find(c => c.lat === initial.lat && c.lng === initial.lng);
            if (candidate?.bounds) map.fitBounds([[candidate.bounds.west, candidate.bounds.south], [candidate.bounds.east, candidate.bounds.north]], { padding: 40, maxZoom: 16 });
            else map.jumpTo({ center: [initial.lng, initial.lat], zoom: 16 });
            placeMarker(initial);
          }
        });
      } catch { if (!controller.signal.aborted) callbacks.current.onAvailableChange(false); }
    }
    function placeMarker(position: Point) {
      if (!map) return;
      markerRef.current = new maplibregl.Marker({ color: "#1558a6", draggable: true }).setLngLat([position.lng, position.lat]).addTo(map);
      markerRef.current.on("dragend", () => {
        const p = markerRef.current?.getLngLat();
        if (p) callbacks.current.onPlace({ lat: p.lat, lng: p.lng });
      });
    }
    void initialize();
    return () => { controller.abort(); markerRef.current?.remove(); markerRef.current = null; map?.remove(); mapRef.current = null; };
  }, [area, candidates]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !pin) return;
    callbacks.current.onAvailableChange(false);
    if (!markerRef.current) {
      markerRef.current = new maplibregl.Marker({ color: "#1558a6", draggable: true }).setLngLat([pin.lng, pin.lat]).addTo(map);
      markerRef.current.on("dragend", () => {
        const p = markerRef.current?.getLngLat();
        if (p) callbacks.current.onPlace({ lat: p.lat, lng: p.lng });
      });
    } else markerRef.current.setLngLat([pin.lng, pin.lat]);
    map.easeTo({ center: [pin.lng, pin.lat] });
  }, [pin]);
  return <div ref={element} aria-label="Service location map" style={{ width: "100%", height: 420, borderRadius: 8 }} />;
}
