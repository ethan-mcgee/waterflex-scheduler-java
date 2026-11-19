"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { errorMessage, readResponse, reservationResult } from "@/lib/contracts";
import { clockLabel } from "@/lib/workOrdersDayCore";
import { demandCell, hoursLabel, inArea, weekLabel, weekdays } from "@/lib/recurringDemandCore";
import type { DemandData, DemandDay, DemandSeries } from "@/lib/recurringDemand";
import { Button, FilterBar, Panel } from "@/app/portal/ui";
import { SelectField, TextField } from "@/app/portal/ui/fields";
import SeriesList from "./SeriesList";
import ReservationActions from "./ReservationActions";
import { customerOf, dayLabel } from "./labels";
import styles from "./recurring.module.css";

export type RecurringTab = "demand" | "list";
interface Filters { type: string; branch: string; place: string }
const NO_FILTERS: Filters = { type: "", branch: "", place: "" };

function matches(series: DemandSeries, filters: Filters): boolean {
  if (filters.type !== "" && series.workOrderType !== filters.type) return false;
  if (filters.branch !== "" && series.branchId !== filters.branch) return false;
  const place = filters.place.trim().toLowerCase();
  return place === "" || series.place.toLowerCase().includes(place);
}

/**
 * Recurring work orders: the demand grid (due customers by branch and week against free technician time), the
 * selected week's schedule, and reserving times for the customers still open. Reservations are planning marks the
 * scheduler does not see yet; booking one takes its work order in, to be placed on the calendar.
 */
/** A day's longest free stretch: a visit fits only within one. */
function longestLabel(item: DemandDay): string {
  const longest = item.free.reduce((most, span) => Math.max(most, span.end - span.start), 0);
  return longest < 60 ? `${longest} min` : hoursLabel(longest);
}

export default function RecurringDemand({ data, tab }: { data: DemandData; tab: RecurringTab }) {
  const router = useRouter();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [selected, setSelected] = useState<{ branchId: string; week: string } | null>(null);
  const series = useMemo(() => data.series.filter(item => matches(item, filters)), [data.series, filters]);
  const types = [...new Set(data.series.flatMap(item => item.workOrderType === null ? [] : [item.workOrderType]))].sort();
  const branches = data.branches.filter(branch => filters.branch === "" || branch.id === filters.branch);
  const unbranched = series.filter(item => item.branchId === null).length;
  const beyond = series.filter(item => item.branchId !== null && item.week === null).length;
  const selectedBranch = selected === null ? undefined : data.branches.find(branch => branch.id === selected.branchId);

  return <>
    <nav className={styles.tabs} aria-label="Recurring views">
      <Link href="/work-orders/recurring?tab=list" aria-current={tab === "list" ? "page" : undefined}>List</Link>
      <Link href="/work-orders/recurring" aria-current={tab === "demand" ? "page" : undefined}>Demand</Link>
    </nav>
    <FilterBar canClear={filters.type !== "" || filters.branch !== "" || filters.place !== ""} onClear={() => setFilters(NO_FILTERS)}>
      <SelectField label="Work order type" value={filters.type} options={types.map(type => ({ value: type, label: type }))}
        onChange={type => setFilters({ ...filters, type })} disabled={types.length === 0} />
      <SelectField label="Branch" value={filters.branch} options={data.branches.map(branch => ({ value: branch.id, label: branch.name }))}
        onChange={branch => setFilters({ ...filters, branch })} />
      <TextField label="City / ZIP" value={filters.place} onChange={place => setFilters({ ...filters, place })} />
    </FilterBar>
    {tab === "list" ? <SeriesList series={series} branches={data.branches} /> : <div className={styles.layout}>
      <div className={styles.main}>
        <Panel className={styles.gridPanel} label="Due, not booked, by branch and week">
          <div className={styles.panelHead}>
            <h2>Due, not booked, by branch and week</h2>
            <span>Next {data.weeks.length} weeks</span>
          </div>
          <ul className={styles.legend} aria-label="Legend">
            <li><span className={styles.swatchEnough} aria-hidden />Enough free time</li>
            <li><span className={styles.swatchShort} aria-hidden />Short on time</li>
            <li><span className={styles.swatchSelected} aria-hidden />Selected</li>
          </ul>
          <table className={styles.grid}>
            <thead><tr><th><span className="sr-only">Branch</span></th>{data.weeks.map(week => <th key={week} scope="col">{weekLabel(week)}</th>)}</tr></thead>
            <tbody>{branches.map(branch => <tr key={branch.id}>
              <th scope="row">{branch.name}<small>{branch.metroName}</small></th>
              {data.weeks.map(week => {
                const cell = demandCell(series, data.days, branch.id, week, data.today);
                const isSelected = selected?.branchId === branch.id && selected.week === week;
                const short = cell.shortMin > 0;
                return <td key={week}>
                  <button type="button" className={`${styles.cell} ${short ? styles.cellShort : ""} ${isSelected ? styles.cellSelected : ""}`}
                    data-demand-cell={`${branch.id}|${week}`} aria-pressed={isSelected}
                    aria-label={`${branch.name}, ${weekLabel(week)}: ${cell.notBooked} not booked`}
                    onClick={() => setSelected(isSelected ? null : { branchId: branch.id, week })}>
                    <span className={styles.cellCount}><strong>{cell.notBooked}</strong> not booked</span>
                    <span className={styles.cellLine}>{cell.booked} booked · {cell.reserved} reserved · {hoursLabel(cell.freeMin)} free</span>
                    {short && <span className={styles.cellShortLine}>Short {hoursLabel(cell.shortMin, "up")}</span>}
                    {cell.untimed > 0 && <span className={styles.cellShortLine}>{cell.untimed} without usable minutes</span>}
                  </button>
                </td>;
              })}
            </tr>)}</tbody>
          </table>
          <p className={styles.note}>
            Free time is each technician&apos;s shift from today on, less booked visits, approved time off and reserved times. Drive time is not
            counted, so the scheduler may fit less. Overdue customers are counted in the first week.
            {unbranched > 0 && ` ${unbranched} customer${unbranched === 1 ? " has" : "s have"} no branch: no WaterFlex coordinates, or outside every service area.`}
            {beyond > 0 && ` ${beyond} more ${beyond === 1 ? "is" : "are"} due after these weeks.`}
          </p>
        </Panel>
        {selected !== null && selectedBranch !== undefined && <WeekSchedule branchName={selectedBranch.name}
          days={data.days.filter(item => item.branchId === selected.branchId && weekdays(selected.week).includes(item.date))} week={selected.week}
          today={data.today} />}
      </div>
      {selected !== null && selectedBranch !== undefined && <ReservePanel key={`${selected.branchId}|${selected.week}`} branch={selectedBranch}
        week={selected.week} today={data.today} onDone={() => router.refresh()}
        series={series.filter(item => item.branchId === selected.branchId && item.week === selected.week)}
        days={data.days.filter(item => item.branchId === selected.branchId && weekdays(selected.week).includes(item.date))} />}
    </div>}
  </>;
}

/** The selected branch week: each weekday's working technicians, booked visits solid and reservations dashed. */
function WeekSchedule({ branchName, week, days, today }: { branchName: string; week: string; days: readonly DemandDay[]; today: string }) {
  return <Panel className={styles.weekPanel} label="On the schedule">
    <div className={styles.panelHead}><h2>On the schedule · {branchName}, {weekLabel(week)}</h2></div>
    <ul className={styles.legend} aria-label="Week legend">
      <li><span className={styles.swatchBooked} aria-hidden />Booked</li>
      <li><span className={styles.swatchReserved} aria-hidden />Reserved, not sent to the scheduler</li>
    </ul>
    <div className={styles.week}>
      {weekdays(week).map(date => {
        const working = days.filter(item => item.date === date && item.shift !== null);
        return <section key={date} className={styles.weekDay} aria-label={dayLabel(date)} data-week-day={date}>
          <h3>{dayLabel(date)}</h3>
          {working.length === 0 && <p className={styles.muted}>No technician working</p>}
          {working.map(item => <div key={item.technicianId} className={styles.techDay}>
            <p className={styles.muted}>{item.technicianName}{date < today ? "" : ` · ${hoursLabel(item.freeMin)} free`}</p>
            {[...item.visits.map(visit => ({ key: visit.id, startMin: visit.startMin, label: visit.customerName, reserved: false })),
              ...item.reservations.filter(reservation => !reservation.placed).map(reservation =>
                ({ key: reservation.id, startMin: reservation.startMin, label: reservation.customerName, reserved: true }))]
              .sort((a, b) => a.startMin - b.startMin).map(entry => <p key={entry.key} className={entry.reserved ? styles.reservedBlock : styles.bookedBlock}
                data-reserved={entry.reserved ? entry.key : undefined}>
                {clockLabel(entry.startMin)} · {entry.label}
              </p>)}
          </div>)}
        </section>;
      })}
    </div>
  </Panel>;
}

/**
 * Reserving times for the open customers of a branch week: the technician-days with free time, those already in the
 * area chosen first, and each due customer with its reservation's actions.
 */
function ReservePanel({ branch, week, today, series, days, onDone }: {
  branch: DemandData["branches"][number]; week: string; today: string; series: readonly DemandSeries[]; days: readonly DemandDay[]; onDone: () => void;
}) {
  const open = series.flatMap(item => item.reservation === null && item.durationMin !== null ? [{ ...item, durationMin: item.durationMin }] : []);
  const located = open.flatMap(item => item.location === null ? [] : [item.location]);
  const usable = days.filter(item => item.date >= today && item.freeMin > 0);
  const near = usable.filter(item => inArea(item.visits.flatMap(visit => visit.location === null ? [] : [visit.location]), located));
  const others = usable.filter(item => !near.includes(item));
  const key = (item: DemandDay) => `${item.technicianId}|${item.date}`;
  const [chosen, setChosen] = useState<ReadonlySet<string>>(() => new Set((near.length > 0 ? near : others).map(key)));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const chosenDays = [...near, ...others].filter(item => chosen.has(key(item)));
  const freeMin = chosenDays.reduce((sum, item) => sum + item.freeMin, 0);
  const openMin = open.reduce((sum, item) => sum + item.durationMin, 0);

  function toggle(item: DemandDay) {
    const next = new Set(chosen);
    if (next.has(key(item))) next.delete(key(item)); else next.add(key(item));
    setChosen(next);
  }

  async function reserve() {
    setBusy(true); setError(null); setOutcome(null);
    try {
      const response = await fetch("/api/recurring/reservations", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ branchId: branch.id, week, days: chosenDays.map(item => ({ technicianId: item.technicianId, date: item.date })),
          seriesIds: open.map(item => item.id) }) });
      const result = await readResponse(response, reservationResult);
      const names = result.unplaced.map(id => open.find(item => item.id === id)).flatMap(item => item === undefined ? [] : [customerOf(item)]);
      setOutcome((result.reserved.length === 0 ? "No times were reserved." : `Reserved ${result.reserved.length} time${result.reserved.length === 1 ? "" : "s"}.`)
        + (names.length > 0 ? ` ${names.length} did not fit in one free stretch on the chosen days: ${names.join(", ")}.` : ""));
      onDone();
    } catch (failure) { setError(errorMessage(failure, "The times could not be reserved. Try again.")); }
    finally { setBusy(false); }
  }

  const dayRow = (item: DemandDay) => <li key={key(item)}>
    <label>
      <input type="checkbox" checked={chosen.has(key(item))} onChange={() => toggle(item)} />
      {dayLabel(item.date)} · {item.technicianName}
    </label>
    <span>{hoursLabel(item.freeMin)} free, longest {longestLabel(item)}</span>
  </li>;

  return <aside className={styles.reservePanel} aria-label="Reserve times">
    <div className={styles.reserveHead}>
      <h2>{open.length === 0 ? "No customers left to reserve" : `Reserve times for ${open.length} customer${open.length === 1 ? "" : "s"}`}</h2>
      <p>{branch.name} · due {weekLabel(week)} · not booked</p>
    </div>
    {open.length > 0 && <div className={styles.reserveBody}>
      <h3>Days a technician is already in the area</h3>
      {near.length === 0 ? <p className={styles.muted}>No technician has a visit near these customers this week.</p>
        : <ul className={styles.dayList} aria-label="Days in the area">{near.map(dayRow)}</ul>}
      {others.length > 0 && <>
        <h3>Other working days</h3>
        <ul className={styles.dayList} aria-label="Other working days">{others.map(dayRow)}</ul>
      </>}
      <p className={freeMin >= openMin ? styles.enough : styles.shortText}>
        {hoursLabel(freeMin)} free on the chosen days for {hoursLabel(openMin, "up")} due. A visit needs one free stretch as long as its time.
      </p>
      <p className={styles.muted}>
        Each customer, earliest due first, gets the first free time on the chosen days that fits the time its reason codes suggest. Reserved
        times are not sent to the scheduler yet: book each one to place its work order on the calendar.
      </p>
      {error !== null && <p className={styles.error} role="alert">{error}</p>}
      <div className={styles.reserveActions}>
        <Button onClick={() => void reserve()} disabled={busy || chosenDays.length === 0}>
          Reserve {open.length} time{open.length === 1 ? "" : "s"}
        </Button>
      </div>
    </div>}
    {outcome !== null && <p className={styles.outcome} role="status">{outcome}</p>}
    <div className={styles.reserveBody}>
      <h3>Customers due</h3>
      <ul className={styles.customerList} aria-label="Customers due">
        {series.map(item => <li key={item.id} data-series={item.id}>
          <p><strong>{customerOf(item)}</strong> · {item.place}</p>
          <p className={styles.muted}>
            {item.codes.map(code => code.name).join(", ")} · due {dayLabel(item.dueDate)}{item.overdue ? " (overdue)" : ""}
            {item.durationMin === null ? " · no usable minutes" : ` · ${item.durationMin} min`}
          </p>
          <ReservationActions series={item} metroId={branch.metroId} onDone={onDone} />
        </li>)}
      </ul>
    </div>
  </aside>;
}
