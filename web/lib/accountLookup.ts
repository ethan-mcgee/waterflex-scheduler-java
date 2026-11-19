import type { Account } from "@prisma/client";
import { prisma } from "./prisma";

/**
 * Looking up a customer's WaterFlex account. Today the accounts are a local copy imported from WaterFlex's export
 * (scripts/import-accounts.ts); when the portal can ask WaterFlex directly, only this module changes. Everything else
 * reads accounts through these two functions.
 */

export interface WaterFlexAccount {
  /** The portal's own row, which a customer booked from the account points to. */
  id: string;
  accountNumber: string;
  name: string | null;
  phone: string | null;
  email: string | null;
  line1: string;
  line2: string | null;
  city: string;
  state: string;
  postalCode: string;
  /** Where WaterFlex geocoded the account; null when it has no coordinates, which can be booked only as a follow-up. */
  location: { lat: number; lng: number } | null;
  importedAt: string;
}

/** The shortest search worth running: one character matches too much of a long account list. */
export const MIN_ACCOUNT_QUERY = 2;
const SEARCH_LIMIT = 10;

function shape(row: Account): WaterFlexAccount {
  const location = row.lat !== null && row.lng !== null ? { lat: row.lat, lng: row.lng } : null;
  if ((row.lat === null) !== (row.lng === null)) throw new Error(`Account ${row.accountNumber} has only one coordinate`);
  return { id: row.id, accountNumber: row.accountNumber, name: row.name, phone: row.phone, email: row.email, line1: row.line1,
    line2: row.line2, city: row.city, state: row.state, postalCode: row.postalCode, location, importedAt: row.importedAt.toISOString() };
}

/** Accounts whose number starts with the query, or whose name or street holds it; at most ten, by account number. */
export async function searchAccounts(clientId: string, query: string): Promise<WaterFlexAccount[]> {
  const term = query.trim();
  if (term.length < MIN_ACCOUNT_QUERY) return [];
  const rows = await prisma.account.findMany({
    where: { clientId, OR: [{ accountNumber: { startsWith: term } }, { name: { contains: term, mode: "insensitive" } },
      { line1: { contains: term, mode: "insensitive" } }] },
    orderBy: { accountNumber: "asc" }, take: SEARCH_LIMIT,
  });
  return rows.map(shape);
}

/** One of this client's accounts by its number, or null when the client has no such account. */
export async function findAccount(clientId: string, accountNumber: string): Promise<WaterFlexAccount | null> {
  const row = await prisma.account.findUnique({ where: { clientId_accountNumber: { clientId, accountNumber: accountNumber.trim() } } });
  return row === null ? null : shape(row);
}

/** How many accounts this client has, how many lack coordinates, and when the latest import ran. */
export async function accountSummary(clientId: string): Promise<{ total: number; withoutLocation: number; lastImportedAt: string | null }> {
  const [total, withoutLocation, latest] = await Promise.all([
    prisma.account.count({ where: { clientId } }),
    prisma.account.count({ where: { clientId, lat: null } }),
    prisma.account.aggregate({ where: { clientId }, _max: { importedAt: true } }),
  ]);
  return { total, withoutLocation, lastImportedAt: latest._max.importedAt?.toISOString() ?? null };
}
