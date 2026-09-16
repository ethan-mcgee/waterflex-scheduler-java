"use client";

import { useEffect, useRef } from "react";
import maplibregl from "maplibre-gl";
import { localMapStyle } from "@/lib/localMapStyle";
import { TECH_COLORS } from "@/app/dispatch/colors";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";

export default function DispatchMap({ technicians, appointments, timezone }: {
  technicians: BoardTechnician[];
  appointments: BoardAppointment[];
  timezone: string;
}) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!element.current) return;
    const points = [
      ...technicians.map((tech) => [tech.homeLng, tech.homeLat] as [number, number]),
      ...appointments.map((appointment) => [appointment.lng, appointment.lat] as [number, number]),
    ];
    const center: [number, number] = points.length ? [
      points.reduce((sum, point) => sum + point[0], 0) / points.length,
      points.reduce((sum, point) => sum + point[1], 0) / points.length,
    ] : [-95.9345, 41.2565];
    const map = new maplibregl.Map({ container: element.current, style: localMapStyle, center, zoom: 10 });
    map.addControl(new maplibregl.NavigationControl());
    const markers: maplibregl.Marker[] = [];
    map.on("load", () => {
      technicians.forEach((tech, index) => {
        const color = TECH_COLORS[index % TECH_COLORS.length];
        const stops = appointments.filter((appointment) => appointment.technicianId === tech.id)
          .sort((left, right) => left.sequence - right.sequence);
        markers.push(new maplibregl.Marker({ color })
          .setLngLat([tech.homeLng, tech.homeLat])
          .setPopup(new maplibregl.Popup().setText(`${tech.name}: home base`))
          .addTo(map));
        const route = [[tech.homeLng, tech.homeLat], ...stops.map((stop) => [stop.lng, stop.lat])];
        if (stops.length > 0) {
          const id = `route-${index}`;
          map.addSource(id, { type: "geojson", data: {
            type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: route },
          } });
          map.addLayer({ id, source: id, type: "line", paint: { "line-color": color, "line-width": 3 } });
        }
        stops.forEach((stop) => {
          const arrival = new Intl.DateTimeFormat("en-US", {
            timeZone: timezone, hour: "numeric", minute: "2-digit",
          }).format(new Date(stop.plannedStart));
          markers.push(new maplibregl.Marker({ color })
            .setLngLat([stop.lng, stop.lat])
            .setPopup(new maplibregl.Popup().setText(`${tech.name}: ${stop.customerName}, ${stop.serviceName}, ${arrival}`))
            .addTo(map));
        });
      });
    });
    return () => { markers.forEach((marker) => marker.remove()); map.remove(); };
  }, [technicians, appointments, timezone]);
  return <div ref={element} style={{ width: "100%", height: "100%" }} />;
}
