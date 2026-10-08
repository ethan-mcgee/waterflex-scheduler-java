/**
 * Clients the portal schedules for. The portal stands in for WaterFlex Software: each client is one public API tenant
 * whose token is in the environment, and each client's dealerships, technicians and customers are kept apart.
 */

/** The client that owned every row before clients existed, and the one fixtures use. */
export const DEFAULT_CLIENT_ID = "default";

/** The cookie that remembers which client the portal is acting for. */
export const CLIENT_COOKIE = "wf_client";

/** Client IDs are lowercase slugs, as the database checks. */
export const CLIENT_ID = /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$/;

export interface ClientSummary { id: string; name: string }

/**
 * The client a request acts for: the remembered client while it still exists, otherwise the default client, otherwise
 * the first client by name. Null only when there are no clients at all.
 */
export function chooseClient(remembered: string | undefined, clients: readonly ClientSummary[]): ClientSummary | null {
  const byId = (id: string | undefined) => (id == null ? undefined : clients.find(client => client.id === id));
  return byId(remembered) ?? byId(DEFAULT_CLIENT_ID) ?? [...clients].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id))[0] ?? null;
}
