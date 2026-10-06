/** Format integer cents without converting a large dollar amount to floating point. */
export function formatCents(cents: number): string {
  if (!Number.isSafeInteger(cents)) throw new Error("Invalid integer cents");
  const exact = BigInt(cents);
  const magnitude = exact < 0n ? -exact : exact;
  return `${exact < 0n ? "-" : ""}${magnitude / 100n}.${(magnitude % 100n).toString().padStart(2, "0")}`;
}
