import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { toStored, type SolverSettingsInput, type StoredSolverSettings } from "./clientSettings";

const columns = {
  regularHourly: true, overtimeHourly: true, mileagePerMile: true, travelBufferPct: true, travelBufferMinutes: true,
  fairnessBudget: true, offerLimit: true, bookingHorizonWeekdays: true, version: true, updatedAt: true,
} as const;

/** The client's settings, or null when the client has not configured them. */
export async function loadSolverSettings(clientId: string): Promise<StoredSolverSettings | null> {
  return prisma.clientSolverSettings.findUnique({ where: { clientId }, select: columns });
}

export type SaveOutcome = { saved: StoredSolverSettings } | { stale: true };

/**
 * Saves the client's settings if nobody saved since `expectedVersion` was read (null: the client had none). Another
 * save in between is reported as stale rather than overwritten.
 */
export async function storeSolverSettings(clientId: string, expectedVersion: number | null, input: SolverSettingsInput): Promise<SaveOutcome> {
  const values = toStored(input);
  if (expectedVersion === null) {
    try {
      return { saved: await prisma.clientSolverSettings.create({ data: { clientId, ...values }, select: columns }) };
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") return { stale: true };
      throw error;
    }
  }
  return prisma.$transaction(async tx => {
    const updated = await tx.clientSolverSettings.updateMany({ where: { clientId, version: expectedVersion },
      data: { ...values, version: { increment: 1 }, updatedAt: new Date() } });
    if (updated.count !== 1) return { stale: true } as const;
    const saved = await tx.clientSolverSettings.findUnique({ where: { clientId }, select: columns });
    if (saved === null) throw new Error(`Settings for client ${clientId} disappeared after saving`);
    return { saved };
  });
}
