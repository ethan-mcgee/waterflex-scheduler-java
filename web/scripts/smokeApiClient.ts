// Connects an existing portal client to a running scheduler's public API for one smoke run, as an operator would: a
// token from the scheduler's command line for a tenant with the client's ID, put in the client's environment
// variable, and solver settings if it has none. The returned cleanup revokes the token and removes settings the run
// added. The tenant stays in the scheduler's database: its request records refer to it, and without a token it
// cannot call.
import { prisma } from "../lib/prisma";
import { issueToken, revokeToken } from "./smokeTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { tokenVariable } from "../lib/schedulerApi";

export async function connectSmokeClient(clientId: string, baseUrl: string, label: string): Promise<() => Promise<void>> {
  process.env.SCHEDULER_API_URL = baseUrl;
  const hadSettings = (await prisma.clientSolverSettings.findUnique({ where: { clientId } })) !== null;
  if (!hadSettings) {
    const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
      travelBufferPercent: "0", travelBufferMinutes: 0, fairnessBudgetPercent: "2", offerLimit: 4, windowHours: 4, bookingHorizonWeekdays: 3, horizonMode: "FIXED", horizonLeadWeekdays: 2, horizonFullPercent: 85, dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, bookingDeadlineMs: 5000, regularWindowThreshold: 2 });
    if (!("saved" in saved)) throw new Error(`Solver settings for ${clientId} were refused`);
  }
  const { id: tokenId, token } = await issueToken(clientId, clientId, label);
  process.env[tokenVariable(clientId)] = token;
  return async () => {
    await revokeToken(tokenId);
    if (!hadSettings) await prisma.clientSolverSettings.delete({ where: { clientId } });
  };
}
