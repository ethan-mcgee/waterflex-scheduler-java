"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, ListFilter } from "lucide-react";
import {
  cardMatches, hasFilters, NO_FILTERS, waitingMatches, type CardFilters, type WorkOrdersDay,
} from "@/lib/workOrdersDayCore";
import { Chip, EmptyState, FilterBar, Panel, STATUS_LABEL, StatusIcon, type WorkOrderStatus } from "../ui";
import { SelectField, TextField } from "../ui/fields";
import CalendarGrid from "./CalendarGrid";
import RoutesMap from "./RoutesMap";
import styles from "./schedule.module.css";

export type ScheduleViewName = "calendar" | "map" | "dual";

const STATUSES: readonly WorkOrderStatus[] = ["SCHEDULED", "COMPLETED"];
const NOT_YET = "Not available yet: the portal does not keep this for work orders.";

/** "Tuesday, 10/15/24", as the design heads the day. */
function dayHeading(date: string): string {
  const instant = new Date(`${date}T12:00:00.000Z`);
  const weekday = new Intl.DateTimeFormat("en-US", { weekday: "long", timeZone: "UTC" }).format(instant);
  const short = new Intl.DateTimeFormat("en-US", { month: "2-digit", day: "2-digit", year: "2-digit", timeZone: "UTC" }).format(instant);
  return `${weekday}, ${short}`;
}

/** The schedule's filters, technician picker, day stepper and the chosen view. Filtering happens here, on the page. */
export default function ScheduleWorkspace({ day, view, isToday, previousHref, nextHref }: {
  day: WorkOrdersDay; view: ScheduleViewName; isToday: boolean; previousHref: string; nextHref: string;
}) {
  const [filters, setFilters] = useState<CardFilters>(NO_FILTERS);
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const set = (change: Partial<CardFilters>) => setFilters(current => ({ ...current, ...change }));

  const shownTechnicians = useMemo(() => day.technicians.filter(tech => !hidden.has(tech.id)), [day.technicians, hidden]);
  const cards = useMemo(() => day.cards.filter(card => cardMatches(card, filters)), [day.cards, filters]);
  const waiting = useMemo(() => day.waiting.filter(order => waitingMatches(order, filters)), [day.waiting, filters]);
  const hiddenTechnicians = day.technicians.filter(tech => hidden.has(tech.id));
  const filtered = hasFilters(filters);
  const nothingMatches = filtered && cards.length === 0 && waiting.length === 0 && (day.cards.length > 0 || day.waiting.length > 0);
  const nothingBooked = day.cards.length === 0 && day.waiting.length === 0;

  const picker = (label: string) => (
    <fieldset className={styles.picker}>
      <legend>{label}</legend>
      {shownTechnicians.map(tech => <Chip key={tech.id} onRemove={() => setHidden(current => new Set([...current, tech.id]))} removeLabel={`Hide ${tech.name}`}>
        {view === "calendar" ? tech.name : <><span className={styles.chipDot} style={{ background: tech.color }} />{tech.name}</>}
      </Chip>)}
      {hiddenTechnicians.length > 0 && <select className={styles.pickerMore} value="" aria-label="Show a hidden technician"
        onChange={event => { const id = event.target.value; setHidden(current => new Set([...current].filter(item => item !== id))); }}>
        <option value="">+{hiddenTechnicians.length}</option>
        {hiddenTechnicians.map(tech => <option key={tech.id} value={tech.id}>{tech.name}</option>)}
      </select>}
      {day.technicians.length === 0 && <span className={styles.pickerNone}>No technicians work in {day.metro.name} this day</span>}
    </fieldset>
  );

  const calendar = <CalendarGrid technicians={shownTechnicians} cards={cards} waiting={waiting} types={day.types}
    timezone={day.metro.timezone} isToday={isToday} compact={view === "dual"} />;
  const map = <RoutesMap day={day} technicians={shownTechnicians} cards={cards} waiting={waiting} collapsedByDefault={view === "dual"} />;

  return <>
    <FilterBar canClear={filtered} onClear={() => setFilters(NO_FILTERS)}>
      <SelectField label="Work order type" value={filters.type} onChange={type => set({ type })}
        options={day.types.map(type => ({ value: type.code, label: type.name }))} />
      <SelectField label="Status" value={filters.status} onChange={status => set({ status })}
        options={STATUSES.map(status => ({ value: status, label: STATUS_LABEL[status] }))} />
      <SelectField label="Reason code" value="" options={[]} disabled hint={NOT_YET} />
      <SelectField label="Branch" value="" options={[]} disabled hint={NOT_YET} />
      <TextField label="City / ZIP" value={filters.place} onChange={place => set({ place })} />
    </FilterBar>

    <Panel className={styles.board} label="Schedule">
      <div className={styles.boardHead}>
        <div className={styles.stepper}>
          <Link href={previousHref} aria-label="Previous day"><ChevronLeft size={20} aria-hidden /></Link>
          <h2>{dayHeading(day.date)}</h2>
          <Link href={nextHref} aria-label="Next day"><ChevronRight size={20} aria-hidden /></Link>
        </div>
        {picker(view === "calendar" ? "Display calendar for" : "Show routes for")}
      </div>

      {nothingMatches ? <EmptyState icon={ListFilter} title="No work orders match these filters"
        actions={<button type="button" className={styles.outlinedButton} onClick={() => setFilters(NO_FILTERS)}>Clear filters</button>}>
        Try removing a filter, or check another day.
      </EmptyState>
      : view === "calendar" ? calendar
      : view === "map" ? map
      : <div className={styles.dual}>{calendar}{map}</div>}

      {nothingBooked && !nothingMatches && <p className={styles.quiet} role="status">No work orders on {dayHeading(day.date)} in {day.metro.name}.</p>}

      {view !== "map" && <div className={styles.legend} aria-label="Legend">
        <span>Work order status</span>
        {STATUSES.map(status => <span key={status} className={styles.legendItem}><StatusIcon status={status} />{STATUS_LABEL[status]}</span>)}
        <span className={styles.legendNote}>Colour = work order type</span>
        {day.types.map(type => <span key={type.code} className={styles.legendItem}><span className={styles.swatch} style={{ background: type.background }} />{type.name}</span>)}
      </div>}
    </Panel>
  </>;
}
