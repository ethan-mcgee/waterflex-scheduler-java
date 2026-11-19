import Link from "next/link";
import type { ReactNode } from "react";
import { CalendarDays, Columns2, Map as MapIcon, MapPinOff } from "lucide-react";
import { date as dateContract } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { tomorrowInTz } from "@/lib/date";
import { clientMetros, loadWorkOrdersDay } from "@/lib/workOrdersDay";
import { EmptyState, PageHeader, SegmentedToggle, type ToggleItem } from "@/app/portal/ui";
import { loadCalendar } from "./loadCalendar";
import ScheduleWorkspace, { type ScheduleViewName } from "./ScheduleWorkspace";
import { scheduleHref } from "./scheduleHref";
import styles from "./schedule.module.css";

export const dynamic = "force-dynamic";

const VIEWS: ReadonlyArray<{ key: ScheduleViewName; label: string; icon: ToggleItem["icon"] }> = [
  { key: "day", label: "Calendar", icon: CalendarDays },
  { key: "map", label: "Map View", icon: MapIcon },
  { key: "dual", label: "Dual View", icon: Columns2 },
];

function validDate(value: string | undefined): value is string {
  return value !== undefined && dateContract.safeParse(value).success;
}

/** The view a link asks for. The weekly grid is gone: a `view=week` or `week` link, kept from older links, opens the day. */
function viewOf(view: string | undefined): ScheduleViewName {
  return view === "map" || view === "dual" ? view : "day";
}

export default async function WorkOrdersSchedulePage({ searchParams: query }: {
  searchParams: Promise<{ view?: string; date?: string; week?: string; metroId?: string; placeJob?: string }>;
}) {
  const searchParams = await query;
  const clientId = (await activeClient()).id;
  const metros = await clientMetros(clientId);
  const metro = searchParams.metroId ? metros.find(item => item.id === searchParams.metroId) : metros[0];
  const header = (actions?: ReactNode) => <PageHeader crumbs={["Work Orders", "Schedule"]} title="Work Orders Schedule" actions={actions} />;
  if (metro === undefined) {
    return <>
      {header()}
      <EmptyState icon={MapPinOff} title={searchParams.metroId ? "This metro is not available" : "No metro is set up yet"}>
        {searchParams.metroId ? "The selected metro belongs to another dealership or no longer exists." : "Add a depot under Branches to see its work orders here."}
      </EmptyState>
    </>;
  }

  const view = viewOf(searchParams.view);
  // The calendar opens on the next day by default: the day dispatchers plan, since today's routes are already out.
  const date = validDate(searchParams.date) ? searchParams.date : validDate(searchParams.week) ? searchParams.week : tomorrowInTz(metro.timezone);

  const [calendar, day] = await Promise.all([
    view === "map" ? null : loadCalendar(clientId, metro, date),
    view === "map" || view === "dual" ? loadWorkOrdersDay(clientId, metro, date) : null,
  ]);

  return <>
    {header(<SegmentedToggle label="Schedule view" selected={view}
      items={VIEWS.map(item => ({ ...item, href: scheduleHref({ metroId: metro.id, view: item.key, date }) }))} />)}
    {metros.length > 1 && <nav className={styles.metros} aria-label="Schedule metro">
      {metros.map(item => <Link key={item.id} href={scheduleHref({ metroId: item.id, view, date })}
        aria-current={item.id === metro.id ? "page" : undefined}>{item.name}</Link>)}
    </nav>}
    <ScheduleWorkspace key={`${metro.id}|${view}`} view={view} day={day} metroId={metro.id} timezone={metro.timezone} date={date}
      calendar={calendar === null ? null : { ...calendar, metroId: metro.id, timezone: metro.timezone, date, placeJobId: searchParams.placeJob ?? null }} />
  </>;
}
