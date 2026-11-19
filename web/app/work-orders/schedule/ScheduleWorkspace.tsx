"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, ListFilter } from "lucide-react";
import { cardMatches, hasFilters, NO_FILTERS, waitingMatches, type CardFilters, type WorkOrdersDay } from "@/lib/workOrdersDayCore";
import { Chip, EmptyState, FilterBar, Panel, STATUS_LABEL, type WorkOrderStatus } from "@/app/portal/ui";
import { SelectField, TextField } from "@/app/portal/ui/fields";
import type { CalendarData } from "./loadCalendar";
import RoutesMap from "./RoutesMap";
import ScheduleView from "./ScheduleView";
import styles from "./schedule.module.css";

/** Day and Week are the calendar; Map draws the day's routes; Dual is the day calendar beside the map. */
export type ScheduleViewName = "day" | "week" | "map" | "dual";

const STATUSES: readonly WorkOrderStatus[] = ["SCHEDULED", "COMPLETED"];
const NOT_YET = "Not available yet: the portal does not keep this for work orders.";

/** "Tuesday, 10/15/24", as the design heads the day. */
function dayHeading(date: string): string {
  const instant = new Date(`${date}T12:00:00.000Z`);
  const weekday = new Intl.DateTimeFormat("en-US", { weekday: "long", timeZone: "UTC" }).format(instant);
  const short = new Intl.DateTimeFormat("en-US", { month: "2-digit", day: "2-digit", year: "2-digit", timeZone: "UTC" }).format(instant);
  return `${weekday}, ${short}`;
}

export interface CalendarProps extends CalendarData {
  metroId: string;
  timezone: string;
  date: string;
  placeJobId: string | null;
}

/**
 * The schedule's filters, technician picker and the chosen view. Filters and the picker apply on the page: the
 * calendar hides what they leave out but still works out places from every visit.
 */
export default function ScheduleWorkspace({ view, calendar, day, previousHref, nextHref }: {
  view: ScheduleViewName;
  /** The calendar week, for the Day, Week and Dual views. */
  calendar: CalendarProps | null;
  /** The metro day's routes, for the Map and Dual views. */
  day: WorkOrdersDay | null;
  previousHref: string;
  nextHref: string;
}) {
  const [filters, setFilters] = useState<CardFilters>(NO_FILTERS);
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const set = (change: Partial<CardFilters>) => setFilters(current => ({ ...current, ...change }));
  const filtered = hasFilters(filters);

  const technicians = calendar?.technicians ?? day?.technicians ?? [];
  const shownTechnicians = technicians.filter(tech => !hidden.has(tech.id));
  const hiddenTechnicians = technicians.filter(tech => hidden.has(tech.id));
  // The filters offer what is on screen: the calendar week's work orders, or the map day's.
  const onScreen = calendar !== null ? [...calendar.appointments, ...calendar.jobs] : [...(day?.cards ?? []), ...(day?.waiting ?? [])];
  const typeOptions = [...new Set(onScreen.flatMap(item => item.workOrder.workOrderType ?? []))].sort((a, b) => a.localeCompare(b))
    .map(type => ({ value: type, label: type }));
  const reasonNames = new Map(onScreen.flatMap(item => item.workOrder.reasonCodes.map(code => [code.code, code.name] as const)));
  const reasonOptions = [...reasonNames.entries()].sort(([, a], [, b]) => a.localeCompare(b)).map(([code, name]) => ({ value: code, label: name }));

  const mapTechnicians = useMemo(() => (day?.technicians ?? []).filter(tech => !hidden.has(tech.id)), [day, hidden]);
  const cards = useMemo(() => (day?.cards ?? []).filter(card => cardMatches(card, filters)), [day, filters]);
  const waiting = useMemo(() => (day?.waiting ?? []).filter(order => waitingMatches(order, filters)), [day, filters]);

  const picker = (
    <fieldset className={styles.picker}>
      <legend>{view === "map" ? "Show routes for" : "Display calendar for"}</legend>
      {shownTechnicians.map(tech => <Chip key={tech.id} onRemove={() => setHidden(current => new Set([...current, tech.id]))} removeLabel={`Hide ${tech.name}`}>
        <span className={styles.chipDot} style={{ background: tech.color }} />{tech.name}
      </Chip>)}
      {hiddenTechnicians.length > 0 && <select className={styles.pickerMore} value="" aria-label="Show a hidden technician"
        onChange={event => { const id = event.target.value; setHidden(current => new Set([...current].filter(item => item !== id))); }}>
        <option value="">+{hiddenTechnicians.length}</option>
        {hiddenTechnicians.map(tech => <option key={tech.id} value={tech.id}>{tech.name}</option>)}
      </select>}
      {technicians.length === 0 && <span className={styles.pickerNone}>No technicians work in this metro {view === "week" ? "this week" : "this day"}</span>}
    </fieldset>
  );

  const calendarView = calendar === null ? null : <ScheduleView metroId={calendar.metroId}
    monday={calendar.monday} view={view === "week" ? "week" : "day"} date={calendar.date} timezone={calendar.timezone}
    technicians={calendar.technicians} appointments={calendar.appointments} absences={calendar.absences} jobs={calendar.jobs}
    services={calendar.services} serviceLegend={calendar.serviceLegend} placeJobId={calendar.placeJobId} version={calendar.version}
    filters={filters} hiddenTechnicianIds={hidden} compact={view === "dual"} />;
  const nothingMatches = filtered && cards.length === 0 && waiting.length === 0 && day !== null && (day.cards.length > 0 || day.waiting.length > 0);
  const mapView = day === null ? null : nothingMatches
    ? <EmptyState icon={ListFilter} title="No work orders match these filters"
      actions={<button type="button" className={styles.outlinedButton} onClick={() => setFilters(NO_FILTERS)}>Clear filters</button>}>
      Try removing a filter, or check another day.
    </EmptyState>
    : <RoutesMap day={day} technicians={mapTechnicians} cards={cards} waiting={waiting} collapsedByDefault={view === "dual"} />;

  return <>
    <FilterBar canClear={filtered} onClear={() => setFilters(NO_FILTERS)}>
      <SelectField label="Work order type" value={filters.type} onChange={type => set({ type })} options={typeOptions}
        disabled={typeOptions.length === 0 && filters.type === ""} hint={typeOptions.length === 0 ? "No work order on screen has a type." : undefined} />
      <SelectField label="Status" value={filters.status} onChange={status => set({ status })}
        options={STATUSES.map(status => ({ value: status, label: STATUS_LABEL[status] }))} />
      <SelectField label="Reason code" value={filters.reason} onChange={reason => set({ reason })} options={reasonOptions} />
      <SelectField label="Branch" value="" options={[]} disabled hint={NOT_YET} />
      <TextField label="City / ZIP" value={filters.place} onChange={place => set({ place })} />
    </FilterBar>

    <Panel className={styles.board} label="Schedule">
      <div className={styles.boardHead}>
        {/* The calendar pages its own days; the map is paged here. */}
        {view === "map" && day !== null && <div className={styles.stepper}>
          <Link href={previousHref} aria-label="Previous day"><ChevronLeft size={20} aria-hidden /></Link>
          <h2>{dayHeading(day.date)}</h2>
          <Link href={nextHref} aria-label="Next day"><ChevronRight size={20} aria-hidden /></Link>
        </div>}
        {picker}
      </div>

      {view === "map" ? mapView
        : view === "dual" ? <div className={styles.dual}><div className={styles.dualCalendar}>{calendarView}</div>{mapView}</div>
        : calendarView}

      {view === "map" && day !== null && day.cards.length === 0 && day.waiting.length === 0 &&
        <p className={styles.quiet} role="status">No work orders on {dayHeading(day.date)} in {day.metro.name}.</p>}
    </Panel>
  </>;
}
