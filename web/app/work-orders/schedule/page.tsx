import type { ReactNode } from "react";
import { CalendarDays, Columns2, Map as MapIcon, MapPinOff } from "lucide-react";
import { date as dateContract } from "@/lib/contracts";
import { activeClient } from "@/lib/activeClient";
import { addCalendarDays, todayInTz } from "@/lib/date";
import { clientMetros, loadWorkOrdersDay } from "@/lib/workOrdersDay";
import { EmptyState, PageHeader, SegmentedToggle, type ToggleItem } from "../ui";
import ScheduleWorkspace, { type ScheduleViewName } from "./ScheduleWorkspace";

export const dynamic = "force-dynamic";

const VIEWS: ReadonlyArray<{ key: ScheduleViewName; label: string; icon: ToggleItem["icon"] }> = [
  { key: "calendar", label: "Calendar", icon: CalendarDays },
  { key: "map", label: "Map View", icon: MapIcon },
  { key: "dual", label: "Dual View", icon: Columns2 },
];

function viewOf(value: string | undefined): ScheduleViewName {
  return value === "map" || value === "dual" ? value : "calendar";
}

export default async function WorkOrdersSchedulePage({ searchParams: query }: {
  searchParams: Promise<{ view?: string; date?: string; metroId?: string }>;
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
        {searchParams.metroId ? "The selected metro belongs to another dealership or no longer exists." : "Add a dealership depot in the classic portal to see its work orders here."}
      </EmptyState>
    </>;
  }

  const view = viewOf(searchParams.view);
  const today = todayInTz(metro.timezone);
  const date = searchParams.date !== undefined && dateContract.safeParse(searchParams.date).success ? searchParams.date : today;
  const href = (changes: { view?: ScheduleViewName; date?: string }) => {
    const params = new URLSearchParams();
    if (searchParams.metroId) params.set("metroId", metro.id);
    const nextView = changes.view ?? view;
    if (nextView !== "calendar") params.set("view", nextView);
    params.set("date", changes.date ?? date);
    return `/work-orders/schedule?${params.toString()}`;
  };
  const day = await loadWorkOrdersDay(clientId, metro, date);

  return <>
    {header(<SegmentedToggle label="Schedule view" selected={view} items={VIEWS.map(item => ({ ...item, href: href({ view: item.key }) }))} />)}
    <ScheduleWorkspace key={`${metro.id}|${date}`} day={day} view={view} isToday={date === today}
      previousHref={href({ date: addCalendarDays(date, -1) })} nextHref={href({ date: addCalendarDays(date, 1) })} />
  </>;
}
