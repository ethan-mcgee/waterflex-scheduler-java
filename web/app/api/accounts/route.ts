import { NextRequest, NextResponse } from "next/server";
import { activeClient } from "@/lib/activeClient";
import { searchAccounts } from "@/lib/accountLookup";
import { accountSearchResponse } from "@/lib/contracts";

/** The active client's WaterFlex accounts matching `q`: an account number's start, or part of a name or street. */
export async function GET(req: NextRequest) {
  const query = req.nextUrl.searchParams.get("q") ?? "";
  const client = await activeClient(req);
  return NextResponse.json(accountSearchResponse.parse({ accounts: await searchAccounts(client.id, query) }));
}
