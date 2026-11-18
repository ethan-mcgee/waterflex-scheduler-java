"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useMemo, useState } from "react";
import { ChevronDown, ChevronUp, Home, PanelLeftClose, PanelLeftOpen, Warehouse } from "lucide-react";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import {
  clockLabel, durationLabel, routeOf, type WaitingWorkOrder, type WorkOrderCard, type WorkOrderTechnician, type WorkOrdersDay,
} from "@/lib/workOrdersDayCore";
import { StatusIcon } from "@/app/portal/ui";
import styles from "./schedule.module.css";

// The dispatch board's map, reused as it is: it fetches the day's road routes and draws every technician's route.
const DispatchMap = dynamic(() => import("@/app/dispatch/DispatchMap"), { ssr: false,
  loading: () => <div className={styles.mapLoading}>Loading road routes...</div> });

function boardTechnician(tech: WorkOrderTechnician): BoardTechnician {
  const shift = tech.shift ?? tech.usualShift;
  return { id: tech.id, name: tech.name, color: tech.color, homeLat: tech.homeLat, homeLng: tech.homeLng, shiftStartMin: shift.startMin, shiftEndMin: shift.endMin };
}

function boardAppointment(card: WorkOrderCard): BoardAppointment {
  return { id: card.appointmentId, technicianId: card.technicianId, sequence: card.sequence, windowStart: card.windowStart, windowEnd: card.windowEnd,
    plannedStart: card.plannedStart, plannedEnd: card.plannedEnd, customerName: card.customerName, serviceName: card.typeName,
    addressLine: `${card.street}, ${card.place}`, lat: card.lat, lng: card.lng, durationMin: card.durationMin,
    geocodePrecision: card.geocodePrecision, pinConfirmedAt: card.pinConfirmedAt, jobStatus: card.jobStatus };
}

function Legend() {
  return (
    <div className={styles.mapLegend} aria-label="Map legend">
      <strong>Legend</strong>
      <span className={styles.legendLine}><i style={{ background: "#c2185b" }} /><i style={{ background: "#5e35b1" }} /><i style={{ background: "#00897b" }} />Line &amp; number colour = technician</span>
      <span className={styles.legendRow}><Warehouse size={14} aria-hidden />Depot<Home size={14} aria-hidden />Home</span>
      <small>Click a pin to see its time, customer and work order type</small>
    </div>
  );
}

function RouteCard({ tech, cards, typeBackground, open, onToggle }: {
  tech: WorkOrderTechnician; cards: readonly WorkOrderCard[]; typeBackground: (code: string) => string; open: boolean; onToggle: () => void;
}) {
  const route = routeOf(tech.id, cards);
  const span = route.startMin !== null && route.endMin !== null ? ` · ${clockLabel(route.startMin)} to ${clockLabel(route.endMin)} · ${durationLabel(route.endMin - route.startMin)}` : "";
  return (
    <section className={styles.routeCard} style={{ borderLeftColor: tech.color }} aria-label={`${tech.name}'s route`}>
      <button type="button" className={styles.routeHead} onClick={onToggle} aria-expanded={open}>
        <span><strong>{tech.name}</strong><small>{route.stops.length} {route.stops.length === 1 ? "stop" : "stops"}{span}{tech.shift === null ? " · off" : ""}</small></span>
        {open ? <ChevronUp size={18} aria-hidden /> : <ChevronDown size={18} aria-hidden />}
      </button>
      {open && <ol className={styles.stops}>
        {route.stops.map((stop, index) => <li key={stop.appointmentId}>
          <span className={styles.stopNumber} style={{ background: tech.color }}>{index + 1}</span>
          <span className={styles.stopBody}>
            <span><mark style={{ background: typeBackground(stop.typeCode) }}>{clockLabel(stop.startMin)}</mark> {stop.place}</span>
            <small>{stop.typeName}</small>
          </span>
          {stop.status && <StatusIcon status={stop.status} />}
        </li>)}
        {route.stops.length === 0 && <li className={styles.quiet}>No stops this day</li>}
      </ol>}
    </section>
  );
}

function UnassignedList({ waiting, metroId, date }: { waiting: readonly WaitingWorkOrder[]; metroId: string; date: string }) {
  return (
    <section className={styles.routeCard} aria-label="Unassigned work orders">
      <div className={styles.routeHead}>
        <span><strong>Unassigned · {waiting.length}</strong><small>Assign opens the day calendar to place one</small></span>
      </div>
      <ol className={styles.stops}>
        {waiting.map(order => <li key={order.jobId}>
          <span className={styles.stopUnassigned} aria-hidden>?</span>
          <span className={styles.stopBody}><span>{order.place}</span><small>{order.typeName} · {durationLabel(order.durationMin)}</small></span>
          <Link className={styles.assign} href={`/work-orders/schedule?${new URLSearchParams({ metroId, date, placeJob: order.jobId }).toString()}`}
            title="Opens the day calendar with this work order being placed">Assign</Link>
        </li>)}
        {waiting.length === 0 && <li className={styles.quiet}>Nothing waiting</li>}
      </ol>
    </section>
  );
}

/**
 * The design's map view: a routes panel that folds to technician avatars, beside the day's road routes. Every route is
 * drawn; choosing an avatar brings one technician forward and fades the others.
 */
export default function RoutesMap({ day, technicians, cards, waiting, collapsedByDefault }: {
  day: WorkOrdersDay; technicians: readonly WorkOrderTechnician[]; cards: readonly WorkOrderCard[]; waiting: readonly WaitingWorkOrder[];
  collapsedByDefault: boolean;
}) {
  const [expanded, setExpanded] = useState(!collapsedByDefault);
  const [focus, setFocus] = useState<string | null>(null);
  const [openRoutes, setOpenRoutes] = useState<ReadonlySet<string>>(() => new Set(technicians.slice(0, 1).map(tech => tech.id)));
  // The map is given every technician and visit of the day, so each drawn route has its technician; filters shape the panel.
  const mapTechnicians = useMemo(() => day.technicians.map(boardTechnician), [day.technicians]);
  const mapAppointments = useMemo(() => day.cards.map(boardAppointment), [day.cards]);
  const typeBackground = (code: string) => {
    const type = day.types.find(item => item.code === code);
    if (type === undefined) throw new Error(`Work order type ${code} is missing from the day's types`);
    return type.background;
  };
  const stopCount = cards.filter(card => technicians.some(tech => tech.id === card.technicianId)).length;

  return (
    <div className={`${styles.mapLayout} ${expanded ? styles.mapLayoutOpen : ""}`} data-work-orders-map>
      {expanded ? <aside className={styles.routesPanel} aria-label="Routes">
        <div className={styles.routesTitle}>
          <h3>Routes <small>{technicians.length} {technicians.length === 1 ? "technician" : "technicians"} · {stopCount} stops</small></h3>
          <button type="button" className={styles.iconButton} onClick={() => setExpanded(false)} aria-label="Collapse routes"><PanelLeftClose size={18} aria-hidden /></button>
        </div>
        {technicians.map(tech => <RouteCard key={tech.id} tech={tech} cards={cards} typeBackground={typeBackground} open={openRoutes.has(tech.id)}
          onToggle={() => setOpenRoutes(current => { const next = new Set(current); if (!next.delete(tech.id)) next.add(tech.id); return next; })} />)}
        <UnassignedList waiting={waiting} metroId={day.metro.id} date={day.date} />
      </aside>
      : <aside className={styles.avatarRail} aria-label="Technicians">
        <button type="button" className={styles.iconButton} onClick={() => setExpanded(true)} aria-label="Expand routes"><PanelLeftOpen size={18} aria-hidden /></button>
        {technicians.map(tech => <button key={tech.id} type="button" className={styles.avatar} style={{ background: tech.color }}
          aria-pressed={focus === tech.id} title={`Bring ${tech.name}'s route forward`} onClick={() => setFocus(current => current === tech.id ? null : tech.id)}>
          {tech.initials}
        </button>)}
        <span className={styles.avatarUnassigned} title={`${waiting.length} unassigned`} aria-label={`${waiting.length} unassigned`}>?<b>{waiting.length}</b></span>
      </aside>}
      <div className={styles.mapArea}>
        <DispatchMap technicians={mapTechnicians} appointments={mapAppointments} timezone={day.metro.timezone} metroId={day.metro.id} date={day.date}
          focusTechnicianId={focus} overlay={<Legend />} />
      </div>
    </div>
  );
}
