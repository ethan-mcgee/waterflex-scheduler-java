import { NextRequest } from "next/server";
import { placementSaveRequest } from "@/lib/contracts";
import { savePlacement } from "@/lib/apiPlacement";
import { placementRoute } from "@/lib/apiPlacementRoute";

/**
 * Saves a job or a moved appointment at the start the dispatcher chose. The scheduler is asked again with fresh facts,
 * and the save is refused when that start no longer fits, or when it changes the customer's promised day or window
 * without the dispatcher's acknowledgement.
 */
export async function POST(req: NextRequest) {
  return placementRoute(req, placementSaveRequest, "Send metroId, a jobId or an appointmentId, spot, plannedStart and acknowledged.",
    (clientId, { metroId, jobId, appointmentId, spot, plannedStart, acknowledged }, phases) => savePlacement(clientId, metroId,
      { ...(jobId === undefined ? {} : { jobId }), ...(appointmentId === undefined ? {} : { appointmentId }) },
      { technicianId: spot.technicianId, serviceDate: spot.serviceDate,
        ...(spot.afterAppointmentId === undefined ? {} : { afterAppointmentId: spot.afterAppointmentId }),
        ...(spot.beforeAppointmentId === undefined ? {} : { beforeAppointmentId: spot.beforeAppointmentId }) },
      plannedStart, acknowledged, phases));
}
