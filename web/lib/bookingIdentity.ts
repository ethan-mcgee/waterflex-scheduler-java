import { createHash } from "node:crypto";
import { z } from "zod";
import { bookingRequest } from "./contracts";

export function bookingFingerprint(input: z.infer<typeof bookingRequest>): string {
  // Explicit ordering makes semantically identical JSON requests share a retry key.
  return createHash("sha256").update(JSON.stringify([
    input.firstName, input.lastName, input.email, input.phone, input.line1, input.line2 ?? "", input.city,
    input.state, input.postalCode, input.serviceCode, input.confirmedPin?.lat ?? null,
    input.confirmedPin?.lng ?? null, input.confirmedPin?.manuallyConfirmed ?? false, input.followUp ?? false,
  ])).digest("hex");
}
