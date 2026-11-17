import { NextRequest } from "next/server";
import { placementPreviewRequest } from "@/lib/contracts";
import { mondayOfWeek, weekDates } from "@/lib/date";
import { previewPlacement } from "@/lib/apiPlacement";
import { placementRoute } from "@/lib/apiPlacementRoute";

/**
 * Every place on the week's routes where an unscheduled job, or an appointment being moved, could go: one call to the
 * scheduler's route insertions for the whole week, so the page works out every hover and drop from it. Nothing is saved.
 */
export async function POST(req: NextRequest) {
  return placementRoute(req, placementPreviewRequest, "Send metroId, monday, and a jobId or an appointmentId.",
    (clientId, { metroId, monday, jobId, appointmentId }, phases) => previewPlacement(clientId, metroId,
      { ...(jobId === undefined ? {} : { jobId }), ...(appointmentId === undefined ? {} : { appointmentId }) },
      weekDates(mondayOfWeek(monday)), phases));
}
