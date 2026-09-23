"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { z } from "zod";
import { localMapStyle } from "@/lib/localMapStyle";

const tileMetadata = z.object({ bounds: z.tuple([
  z.number().finite().min(-180).max(180), z.number().finite().min(-90).max(90),
  z.number().finite().min(-180).max(180), z.number().finite().min(-90).max(90),
]).refine(([west, south, east, north]) => west < east && south < north) });

export default function DepotPinMap({ lat, lng, onDrag }: {
  lat: number;
  lng: number;
  onDrag: (lat: number, lng: number) => void;
}) {
  const element = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const initialPosition = useRef({ lat, lng });
  const [bounds, setBounds] = useState<[number, number, number, number] | null>(null);
  const [metadataLoaded, setMetadataLoaded] = useState(false);
  const onDragRef = useRef(onDrag);
  onDragRef.current = onDrag;

  useEffect(() => {
    const controller = new AbortController();
    fetch("http://localhost:8083/omaha.json", { signal: controller.signal })
      .then(response => { if (!response.ok) throw new Error("Tiles unavailable"); return response.json() as Promise<unknown>; })
      .then(raw => { const parsed = tileMetadata.parse(raw); if (!controller.signal.aborted) setBounds(parsed.bounds); })
      .catch(() => { if (!controller.signal.aborted) setBounds(null); })
      .finally(() => { if (!controller.signal.aborted) setMetadataLoaded(true); });
    return () => controller.abort();
  }, []);

  const mapAvailable = bounds != null && lng >= bounds[0] && lng <= bounds[2] && lat >= bounds[1] && lat <= bounds[3];

  useEffect(() => {
    if (!element.current || !mapAvailable) return;
    const map = new maplibregl.Map({ container: element.current, style: localMapStyle, center: [initialPosition.current.lng, initialPosition.current.lat], zoom: 15 });
    map.addControl(new maplibregl.NavigationControl(), "top-right");
    const marker = new maplibregl.Marker({ color: "#1558a6", draggable: true }).setLngLat([initialPosition.current.lng, initialPosition.current.lat]).addTo(map);
    marker.on("dragend", () => {
      const position = marker.getLngLat();
      onDragRef.current(position.lat, position.lng);
    });
    mapRef.current = map;
    markerRef.current = marker;
    return () => {
      marker.remove();
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
  }, [mapAvailable]);

  useEffect(() => {
    const map = mapRef.current, marker = markerRef.current;
    if (!map || !marker) return;
    marker.setLngLat([lng, lat]);
    map.easeTo({ center: [lng, lat] });
  }, [lat, lng]);

  return <div style={{ width: "100%", height: "100%" }}>
    {mapAvailable ? <div ref={element} style={{ width: "100%", height: "100%" }} /> :
      <div role="status" style={{ padding: 24, textAlign: "center" }}>
        {metadataLoaded ? "Map tiles are unavailable at this address. You can still confirm the located pin." : "Checking map coverage…"}
        <br />Pin: {lat.toFixed(6)}, {lng.toFixed(6)}
      </div>}
  </div>;
}
