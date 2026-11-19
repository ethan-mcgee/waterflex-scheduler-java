import type { DemandSeries } from "@/lib/recurringDemand";

/** "Tue 11/17". */
export function dayLabel(date: string): string {
  const value = new Date(`${date}T00:00:00Z`);
  return `${value.toLocaleDateString("en-US", { weekday: "short", timeZone: "UTC" })} ${value.getUTCMonth() + 1}/${value.getUTCDate()}`;
}

/** The account's name, or its number when WaterFlex's export has no name. */
export const customerOf = (series: Pick<DemandSeries, "customerName" | "accountNumber">) => series.customerName ?? `Account ${series.accountNumber}`;
