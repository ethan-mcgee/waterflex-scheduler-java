// Connects an existing portal client to a running scheduler's public API for one smoke run, as an operator would: a
// tenant with the client's ID, a token in the client's environment variable, and solver settings if it has none. The
// returned cleanup revokes the token and removes settings the run added. The tenant stays: the scheduler's request
// records refer to it, and without a token it cannot call.
import { randomUUID } from "node:crypto";
import { prisma } from "../lib/prisma";
import { generateTenantToken, tenantTokenSha256 } from "../lib/tenantTokens";
import { storeSolverSettings } from "../lib/clientSettingsStore";
import { tokenVariable } from "../lib/schedulerApi";

export async function connectSmokeClient(clientId: string, baseUrl: string, label: string): Promise<() => Promise<void>> {
  process.env.SCHEDULER_API_URL = baseUrl;
  const hadSettings = (await prisma.clientSolverSettings.findUnique({ where: { clientId } })) !== null;
  if (!hadSettings) {
    const saved = await storeSolverSettings(clientId, null, { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
      travelBufferPercent: "0", travelBufferMinutes: 0, fairnessBudgetPercent: "2", offerLimit: 4, bookingHorizonWeekdays: 3 });
    if (!("saved" in saved)) throw new Error(`Solver settings for ${clientId} were refused`);
  }
  await prisma.tenant.upsert({ where: { id: clientId }, create: { id: clientId, name: clientId }, update: {} });
  const tokenId = randomUUID();
  const token = generateTenantToken();
  await prisma.tenantApiToken.create({ data: { id: tokenId, tenantId: clientId, tokenSha256: tenantTokenSha256(token), label } });
  process.env[tokenVariable(clientId)] = token;
  return async () => {
    await prisma.tenantApiToken.delete({ where: { id: tokenId } });
    if (!hadSettings) await prisma.clientSolverSettings.delete({ where: { clientId } });
  };
}
