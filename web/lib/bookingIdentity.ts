import { createHash } from "node:crypto";
import { z } from "zod";
import type { workOrderRequest } from "./contracts";

export function bookingFingerprint(input: z.infer<typeof workOrderRequest>): string {
  // Explicit ordering makes semantically identical JSON requests share a retry key.
  const place = input.accountNumber !== undefined ? ["ACCOUNT", input.accountNumber] : [input.line1, input.line2 ?? "", input.city,
    input.state, input.postalCode, input.confirmedPin?.lat ?? null, input.confirmedPin?.lng ?? null, input.confirmedPin?.manuallyConfirmed ?? false];
  return createHash("sha256").update(JSON.stringify([
    input.firstName, input.lastName, input.email, input.phone, ...place, input.serviceCode, input.followUp ?? false,
    input.extraReasonCodes ?? [], input.durationMin ?? null, input.workOrderType ?? null, input.purchaseOrder ?? null,
    input.technicianMessage ?? null, input.customerRequest ?? null, input.callFirst ?? null,
  ])).digest("hex");
}
