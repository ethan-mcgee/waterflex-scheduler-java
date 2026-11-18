import { NextRequest } from "next/server";
import { placementUndoRequest } from "@/lib/contracts";
import { undoPlacement } from "@/lib/apiPlacement";
import { placementRoute } from "@/lib/apiPlacementRoute";

/**
 * Undoes a save made on the schedule page: every visit it moved goes back where it was, once, while the days it wrote
 * are still as the save left them. Anything changed since refuses the undo rather than being overwritten.
 */
export async function POST(req: NextRequest) {
  return placementRoute(req, placementUndoRequest, "Send metroId and undoId.",
    (clientId, { undoId }, phases) => undoPlacement(clientId, undoId, phases));
}
