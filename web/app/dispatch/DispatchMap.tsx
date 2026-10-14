"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { maplibregl } from "@/lib/maplibre";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import { isDispatchGeometry, type GeometryResponse } from "@/lib/dispatchGeometry";
import { errorMessage } from "@/lib/contracts";
import { useMapInstance } from "@/app/dispatch/map/useMapInstance";
import { syncRouteLayers } from "@/app/dispatch/map/routeLayers";
import { depotMarkerElement, homeMarkerElement, stopMarkerElement } from "@/app/dispatch/map/markers";
import styles from "@/app/dispatch/dispatch.module.css";

export type MapView = "current" | "proposal" | "compare";
/** A plan's road geometry, or why it could not be drawn; `status` is the HTTP status when the server refused. */
export type GeometryResult = { geometry: GeometryResponse } | { error: string; status: number | null };

const DEFAULT_CENTER: [number, number] = [-95.9345, 41.2565];

async function fetchGeometry(url: string, date: string, phase: "current" | "proposal", signal: AbortSignal): Promise<GeometryResult> {
  const response = await fetch(url, { cache: "no-store", signal });
  const raw: unknown = await response.json().catch(() => undefined);
  if (!response.ok) return { error: errorMessage(raw, `Road geometry unavailable (${response.status})`), status: response.status };
  if (!isDispatchGeometry(raw, date, phase)) return { error: "Invalid road geometry.", status: null };
  return { geometry: raw };
}

type Props = {
  technicians: BoardTechnician[];
  appointments: BoardAppointment[];
  timezone: string;
  metroId: string;
  date: string;
  /** Bumped after the current plan changes, to draw it again. */
  revision?: number;
  /** An improved proposal whose optimized routes are drawn beside the current ones. */
  proposalId?: string | null;
  view?: MapView;
  focusTechnicianId?: string | null;
  changedAppointmentIds?: ReadonlySet<string>;
  /** Technicians whose route the proposal changes; Compare draws only their current routes under the optimized ones. */
  changedTechnicianIds?: ReadonlySet<string>;
  /** Fades the current plan while the optimizer searches. */
  dimmed?: boolean;
  notice?: string | null;
  overlay?: ReactNode;
  onSelectStop?: (appointmentId: string, plan: "current" | "proposal") => void;
  onCurrentGeometry?: (result: GeometryResult) => void;
  onProposalGeometry?: (result: GeometryResult) => void;
};

/** The client's routes for a metro day on the scheduler's road geometry: the current plan, an optimized one, or both. */
export default function DispatchMap({ technicians, appointments, timezone, metroId, date, revision = 0, proposalId = null, view = "current", focusTechnicianId = null,
  changedAppointmentIds, changedTechnicianIds, dimmed = false, notice = null, overlay, onSelectStop, onCurrentGeometry, onProposalGeometry }: Props) {
  const element = useRef<HTMLDivElement>(null);
  const center = useMemo<[number, number]>(() => {
    const points = [...technicians.map(tech => [tech.homeLng, tech.homeLat] satisfies [number, number]),
      ...appointments.flatMap(item => item.lat !== null && item.lng !== null ? [[item.lng, item.lat] satisfies [number, number]] : [])];
    return points.length ? [points.reduce((sum, point) => sum + point[0], 0) / points.length, points.reduce((sum, point) => sum + point[1], 0) / points.length] : DEFAULT_CENTER;
  }, [technicians, appointments]);
  const { map, loaded } = useMapInstance(element, center);
  // Each fetched plan is stored with the request it answers, so a new metro day, revision or proposal starts empty
  // without a reset inside the effect.
  const currentKey = `${metroId}|${date}|${revision}`, proposalKey = proposalId === null ? null : `${proposalId}|${date}`;
  const [currentFetch, setCurrentFetch] = useState<{ key: string; geometry: GeometryResponse | null; error: string | null } | null>(null);
  const [proposalFetch, setProposalFetch] = useState<{ key: string; geometry: GeometryResponse } | null>(null);
  const current = currentFetch !== null && currentFetch.key === currentKey ? currentFetch.geometry : null;
  const proposal = proposalFetch !== null && proposalFetch.key === proposalKey ? proposalFetch.geometry : null;
  const fetchError = currentFetch !== null && currentFetch.key === currentKey ? currentFetch.error : null;
  const callbacks = useRef({ onCurrentGeometry, onProposalGeometry, onSelectStop });
  // The map's handlers read the latest callbacks; refresh them after each commit, never during render.
  useLayoutEffect(() => { callbacks.current = { onCurrentGeometry, onProposalGeometry, onSelectStop }; });

  // The current plan is drawn once per metro day and revision; re-renders and proposal changes never refetch it. It is
  // fetched after the map loads, so React's development double mount does not ask the scheduler twice.
  useEffect(() => {
    if (!loaded) return;
    const request = new AbortController();
    void fetchGeometry(`/api/dispatch/geometry?${new URLSearchParams({ metroId, date })}`, date, "current", request.signal).then(result => {
      if (request.signal.aborted) return;
      setCurrentFetch("geometry" in result ? { key: currentKey, geometry: result.geometry, error: null }
        : { key: currentKey, geometry: null, error: `${result.error} Stop markers remain visible.` });
      callbacks.current.onCurrentGeometry?.(result);
    }).catch((cause: unknown) => {
      if (request.signal.aborted) return;
      const message = errorMessage(cause, "Road routes are unavailable.");
      setCurrentFetch({ key: currentKey, geometry: null, error: `${message} Stop markers remain visible.` });
      callbacks.current.onCurrentGeometry?.({ error: message, status: null });
    });
    return () => request.abort();
  }, [loaded, metroId, date, revision, currentKey]);

  useEffect(() => {
    if (proposalId === null || proposalKey === null) return;
    const request = new AbortController();
    void fetchGeometry(`/api/dispatch/proposals/${encodeURIComponent(proposalId)}/geometry`, date, "proposal", request.signal).then(result => {
      if (request.signal.aborted) return;
      if ("geometry" in result) setProposalFetch({ key: proposalKey, geometry: result.geometry });
      callbacks.current.onProposalGeometry?.(result);
    }).catch((cause: unknown) => {
      if (!request.signal.aborted) callbacks.current.onProposalGeometry?.({ error: errorMessage(cause, "Optimized routes are unavailable."), status: null });
    });
    return () => request.abort();
  }, [proposalId, date, proposalKey]);

  const colorByTech = useMemo(() => new Map(technicians.map(tech => [tech.id, tech.color])), [technicians]);
  const nameByTech = useMemo(() => new Map(technicians.map(tech => [tech.id, tech.name])), [technicians]);
  // A route for a technician the board does not know is not drawn; the map says which one instead.
  const unknownTechnician = useMemo(() => [current, proposal].flatMap(geometry => geometry?.features ?? [])
    .find(feature => !colorByTech.has(feature.properties.technicianId))?.properties.technicianId ?? null, [current, proposal, colorByTech]);
  const error = unknownTechnician !== null ? `Unknown route technician ${unknownTechnician}.` : fetchError;

  // Fit the camera to the current plan once per metro day; later views keep where the dispatcher has panned.
  const fitted = useRef<string | null>(null);
  useEffect(() => {
    if (map === null || !loaded || current === null || fitted.current === `${metroId}|${date}`) return;
    fitted.current = `${metroId}|${date}`;
    const bounds = new maplibregl.LngLatBounds();
    current.features.forEach(feature => feature.geometry.coordinates.forEach(point => bounds.extend(point)));
    current.stops.forEach(stop => bounds.extend([stop.lng, stop.lat]));
    current.endpoints?.forEach(endpoint => { bounds.extend([endpoint.departureLng, endpoint.departureLat]); bounds.extend([endpoint.returnLng, endpoint.returnLat]); });
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding: 56, maxZoom: 14, duration: 0 });
  }, [map, loaded, current, metroId, date]);

  useEffect(() => {
    if (map === null || !loaded || unknownTechnician !== null) return;
    const showProposal = proposal !== null && view !== "current";
    const base = { colorByTech, focusTechnicianId, dimmed };
    syncRouteLayers(map, "current", current, { ...base, visible: !showProposal || view === "compare", ghost: showProposal,
      only: showProposal ? changedTechnicianIds ?? new Set<string>() : null });
    syncRouteLayers(map, "proposal", proposal, { ...base, visible: showProposal, ghost: false, only: null });

    const plan = showProposal ? "proposal" : "current";
    const shown = showProposal ? proposal : current;
    const appointmentById = new Map(appointments.map(item => [item.id, item]));
    const time = new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", minute: "2-digit" });
    const faded = (technicianId: string) => dimmed || (focusTechnicianId !== null && focusTechnicianId !== technicianId);
    const stops = shown?.stops ?? appointments.flatMap(item => item.lat !== null && item.lng !== null
      ? [{ id: item.id, technicianId: item.technicianId, sequence: item.sequence, plannedStart: item.plannedStart, lat: item.lat, lng: item.lng }] : []);
    const markers: maplibregl.Marker[] = [];
    for (const stop of stops) {
      const appointment = appointmentById.get(stop.id);
      const changed = showProposal && (changedAppointmentIds?.has(stop.id) ?? false);
      const label = `${nameByTech.get(stop.technicianId) ?? "Technician"}, stop ${stop.sequence + 1}: ${appointment?.customerName ?? stop.id}, ` +
        `${appointment?.serviceName ?? "visit"}, planned ${time.format(new Date(stop.plannedStart))}${changed ? " (changed)" : ""}`;
      const select = callbacks.current.onSelectStop;
      const marker = new maplibregl.Marker({ element: stopMarkerElement(stop.sequence + 1, colorByTech.get(stop.technicianId) ?? "#69766f", label,
        { changed, dimmed: faded(stop.technicianId), onSelect: select ? () => callbacks.current.onSelectStop?.(stop.id, plan) : undefined }) }).setLngLat([stop.lng, stop.lat]);
      if (!select) marker.setPopup(new maplibregl.Popup().setText(label));
      markers.push(marker.addTo(map));
    }
    // Route anchors follow the depot endpoint policy: a home or depot appears only when a drawn route leaves from or returns to it.
    const routed = new Set(stops.map(stop => stop.technicianId));
    const depots = new Set<string>(), homes = new Set<string>();
    for (const endpoint of shown?.endpoints ?? []) {
      if (!routed.has(endpoint.technicianId)) continue;
      const name = nameByTech.get(endpoint.technicianId) ?? "Technician";
      const anchors: Array<[string, number, number]> = [[endpoint.departure, endpoint.departureLng, endpoint.departureLat], [endpoint.returnTo, endpoint.returnLng, endpoint.returnLat]];
      for (const [anchor, lng, lat] of anchors) {
        const at = `${lng},${lat}`;
        if (anchor === "DEPOT") {
          if (depots.has(at)) continue;
          depots.add(at);
          markers.push(new maplibregl.Marker({ element: depotMarkerElement("Depot") }).setLngLat([lng, lat]).addTo(map));
        } else {
          if (homes.has(`${endpoint.technicianId}|${at}`)) continue;
          homes.add(`${endpoint.technicianId}|${at}`);
          markers.push(new maplibregl.Marker({ element: homeMarkerElement(`${name}'s home`, faded(endpoint.technicianId)) }).setLngLat([lng, lat]).addTo(map));
        }
      }
    }
    return () => markers.forEach(marker => marker.remove());
  }, [map, loaded, unknownTechnician, current, proposal, view, focusTechnicianId, changedAppointmentIds, changedTechnicianIds, dimmed, appointments, timezone, colorByTech, nameByTech]);

  return <div className={styles.mapFrame}>
    <div ref={element} className={styles.mapCanvas} />
    {overlay}
    {notice && <div className={styles.mapNotice} role="status">{notice}</div>}
    {error && <div role="status" className={styles.mapError}>{error}</div>}
  </div>;
}
