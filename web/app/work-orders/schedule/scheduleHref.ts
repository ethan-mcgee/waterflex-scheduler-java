/** The schedule views: the day calendar, the day's routes on a map, and the two side by side. */
export type ScheduleViewName = "day" | "map" | "dual";

/** A link to the schedule for one metro, view and day. The day calendar is the default and leaves `view` out. */
export function scheduleHref({ metroId, view, date }: { metroId: string; view: ScheduleViewName; date: string }): string {
  const params = new URLSearchParams({ metroId });
  if (view !== "day") params.set("view", view);
  params.set("date", date);
  return `/work-orders/schedule?${params.toString()}`;
}
