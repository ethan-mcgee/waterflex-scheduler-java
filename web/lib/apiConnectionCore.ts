import { z } from "zod";

/**
 * Whether the portal can schedule for a client through the public API. Only a verified token is CONNECTED;
 * NOT_CONNECTED means the client's own setup is missing or wrong, UNREACHABLE means the scheduling service did not answer
 * usably. Shared by the status route and the sidebar badge.
 */
export const connectionStatus = z.discriminatedUnion("state", [
  z.object({ state: z.literal("CONNECTED") }).strict(),
  z.object({ state: z.enum(["NOT_CONNECTED", "UNREACHABLE"]), reason: z.string().min(1) }).strict(),
]);
export type ConnectionStatus = z.infer<typeof connectionStatus>;

/** GET /api/clients/connection: the status of the client it was checked for, so a reply for a previous client is ignored. */
export const connectionReply = z.discriminatedUnion("state", [
  z.object({ clientId: z.string().min(1), state: z.literal("CONNECTED") }).strict(),
  z.object({ clientId: z.string().min(1), state: z.enum(["NOT_CONNECTED", "UNREACHABLE"]), reason: z.string().min(1) }).strict(),
]);
export type ConnectionReply = z.infer<typeof connectionReply>;

/** The portal's own error codes from lib/schedulerApi, plus any code the API refuses with. */
export function statusOf(error: { code: string; message: string }): ConnectionStatus {
  if (error.code === "NOT_CONNECTED") return { state: "NOT_CONNECTED", reason: error.message };
  if (error.code === "TRANSPORT" || error.code === "MALFORMED") return { state: "UNREACHABLE", reason: error.message };
  // Any other refusal of the identity check means the service answered but the client cannot use it yet.
  return { state: "NOT_CONNECTED", reason: `The scheduling service refused the identity check: ${error.message}` };
}
