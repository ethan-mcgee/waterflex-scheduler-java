import { statusOf, type ConnectionStatus } from "./apiConnectionCore";
import { connection, SchedulerApiError, verifyTenant } from "./schedulerApi";

/**
 * Checks the client's connection the same way every scheduling call does: settings and token first, then the token's
 * tenant (verified once per process, so a later revocation shows on the next failed call, not here).
 */
export async function connectionStatusOf(clientId: string): Promise<ConnectionStatus> {
  try {
    connection(clientId);
    await verifyTenant(clientId);
    return { state: "CONNECTED" };
  } catch (error) {
    if (error instanceof SchedulerApiError) return statusOf(error);
    throw error;
  }
}
