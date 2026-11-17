import { NextRequest } from "next/server";
import { placementPreviewRequest } from "@/lib/contracts";
import { mondayOfWeek, weekDates } from "@/lib/date";
import { previewPlacement } from "@/lib/apiPlacement";
import { placementRoute } from "@/lib/apiPlacementRoute";

/**
 * Every place on the week's routes where an unscheduled job, or an appointment being moved, could go, from the
 * scheduler's route insertions, so the page works out every hover and drop from one answer. The days on screen come
 * with timed stops and the rest of the week without; a day paged to later is asked for alone. Nothing is saved.
 */
export async function POST(req: NextRequest) {
  return placementRoute(req, placementPreviewRequest,
    "Send metroId, monday, timedDates in that week, withRestOfWeek, and a jobId or an appointmentId.",
    (clientId, { metroId, monday, jobId, appointmentId, timedDates, withRestOfWeek }, phases) => previewPlacement(clientId, metroId,
      { ...(jobId === undefined ? {} : { jobId }), ...(appointmentId === undefined ? {} : { appointmentId }) },
      weekDates(mondayOfWeek(monday)), timedDates, withRestOfWeek, phases));
}
