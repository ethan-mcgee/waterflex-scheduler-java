import type { ServiceCatalog } from "@prisma/client";
import { prisma } from "./prisma";

/**
 * The catalog rows for a work order's codes, in the order given, or the refusal when a code is unknown or retired.
 * Shared by taking a work order in and editing one, so both refuse the same codes.
 */
export async function activeReasonCodes(codes: readonly string[]): Promise<{ codes: ServiceCatalog[] } | { error: string }> {
  const rows = await prisma.serviceCatalog.findMany({ where: { code: { in: [...codes] } } });
  const ordered: ServiceCatalog[] = [];
  for (const code of codes) {
    const row = rows.find(item => item.code === code);
    if (row === undefined || !row.active) return { error: `Reason code ${code} is not currently available.` };
    ordered.push(row);
  }
  if (ordered.length === 0) return { error: "A work order needs a reason code." };
  return { codes: ordered };
}
