"use client";

import { useEffect, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { localMapStyle } from "@/lib/localMapStyle";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import { isDispatchGeometry } from "@/lib/dispatchGeometry";
import { errorMessage } from "@/lib/contracts";

export default function DispatchMap({ technicians, appointments, timezone, metroId, date, runId, phase }: {
  technicians: BoardTechnician[];
  appointments: BoardAppointment[];
  timezone: string;
  metroId: string;
  date: string;
  runId?: string;
  phase: "current" | "before" | "after";
}) {
  const element = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!element.current) return;
    const located = appointments.flatMap(a => a.lat !== null && a.lng !== null ? [{ ...a, lat: a.lat, lng: a.lng }] : []);
    setError(null);
    const points = [
      ...technicians.map((tech) => [tech.homeLng, tech.homeLat] satisfies [number, number]),
      ...located.map((appointment) => [appointment.lng, appointment.lat] satisfies [number, number]),
    ];
    const center: [number, number] = points.length ? [
      points.reduce((sum, point) => sum + point[0], 0) / points.length,
      points.reduce((sum, point) => sum + point[1], 0) / points.length,
    ] : [-95.9345, 41.2565];
    const map = new maplibregl.Map({ container: element.current, style: localMapStyle, center, zoom: 10 });
    map.addControl(new maplibregl.NavigationControl());
    let cancelled = false;
    const request = new AbortController();
    const homeMarkers: maplibregl.Marker[] = [];
    let stopMarkers: maplibregl.Marker[] = [];
    const appointmentById = new Map(appointments.map((appointment) => [appointment.id, appointment]));
    const colorByTech = new Map(technicians.map((tech) => [tech.id, tech.color]));
    const markerForStop = (stop: { id: string; technicianId: string; plannedStart: string; lat: number; lng: number }) => {
      const appointment = appointmentById.get(stop.id);
      const tech = technicians.find((item) => item.id === stop.technicianId);
      const arrival = new Intl.DateTimeFormat("en-US", {
        timeZone: timezone, hour: "numeric", minute: "2-digit",
      }).format(new Date(stop.plannedStart));
      return new maplibregl.Marker({ color: colorByTech.get(stop.technicianId) ?? "#666" })
        .setLngLat([stop.lng, stop.lat])
        .setPopup(new maplibregl.Popup().setText(`${tech?.name ?? "Technician"}: ${appointment?.customerName ?? stop.id}, ${appointment?.serviceName ?? "visit"}, planned ${arrival}`))
        .addTo(map);
    };
    map.on("load", () => {
      technicians.forEach((tech) => homeMarkers.push(new maplibregl.Marker({ color: colorByTech.get(tech.id) })
        .setLngLat([tech.homeLng, tech.homeLat])
        .setPopup(new maplibregl.Popup().setText(`${tech.name}: home base`))
        .addTo(map)));
      stopMarkers = located.map(markerForStop);
      const query = new URLSearchParams({ metroId, date, phase });
      if (runId) query.set("runId", runId);
      void fetch(`/api/dispatch/geometry?${query}`, { cache: "no-store", signal: request.signal })
        .then(async (response) => {
          const raw: unknown = await response.json().catch(() => undefined);
          if (!response.ok) throw new Error(errorMessage(raw, `Road geometry unavailable (${response.status})`));
          return raw;
        })
        .then((geometry) => {
          if (cancelled) return;
          if (!isDispatchGeometry(geometry, date, phase))
            throw new Error("Invalid road geometry");
          if (geometry.endpoints) {
            homeMarkers.forEach(marker => marker.remove());
            homeMarkers.length = 0;
            geometry.endpoints.forEach(endpoint => {
              const tech = technicians.find(item => item.id === endpoint.technicianId);
              if (!tech) throw new Error(`Unknown route technician ${endpoint.technicianId}`);
              homeMarkers.push(new maplibregl.Marker({ color: tech.color }).setLngLat([endpoint.departureLng, endpoint.departureLat])
                .setPopup(new maplibregl.Popup().setText(`${tech.name}: departure`)).addTo(map));
              if (endpoint.departureLat !== endpoint.returnLat || endpoint.departureLng !== endpoint.returnLng)
                homeMarkers.push(new maplibregl.Marker({ color: tech.color }).setLngLat([endpoint.returnLng, endpoint.returnLat])
                  .setPopup(new maplibregl.Popup().setText(`${tech.name}: return`)).addTo(map));
            });
          }
          const routedTechnicians = [...new Set(geometry.features.map(feature => feature.properties.technicianId))];
          routedTechnicians.forEach((id) => { if (!colorByTech.has(id)) throw new Error(`Unknown route technician ${id}`); });
          routedTechnicians.forEach((technicianId, index) => {
            const features = geometry.features.filter((feature) => feature.properties.technicianId === technicianId);
            if (features.length === 0) return;
            const id = `road-route-${index}`;
            map.addSource(id, { type: "geojson", data: { type: "FeatureCollection", features } });
            map.addLayer({ id, source: id, type: "line", paint: { "line-color": colorByTech.get(technicianId) ?? "#666", "line-width": 3 } });
          });
          stopMarkers.forEach((marker) => marker.remove());
          stopMarkers = geometry.stops.map(markerForStop);
          const bounds = new maplibregl.LngLatBounds();
          points.forEach((point) => bounds.extend(point));
          geometry.features.forEach((feature) => feature.geometry.coordinates.forEach((point) => bounds.extend(point)));
          if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 48, maxZoom: 14, duration: 0 });
        })
        .catch((cause: unknown) => { if (!cancelled) setError(`${errorMessage(cause, "Road routes are unavailable")} Stop markers remain visible.`); });
    });
    return () => {
      cancelled = true;
      request.abort();
      stopMarkers.forEach((marker) => marker.remove());
      homeMarkers.forEach((marker) => marker.remove());
      map.remove();
    };
  }, [technicians, appointments, timezone, metroId, date, runId, phase]);
  return <div style={{ width: "100%", height: "100%", position: "relative" }}>
    <div ref={element} style={{ width: "100%", height: "100%" }} />
    {error && <div role="status" style={{ position: "absolute", bottom: 12, left: 12, padding: 8, background: "white", color: "#8a1f11" }}>{error}</div>}
  </div>;
}
