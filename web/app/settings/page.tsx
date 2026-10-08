import { activeClient } from "@/lib/activeClient";
import { toView } from "@/lib/clientSettings";
import { loadSolverSettings } from "@/lib/clientSettingsStore";
import SolverSettingsForm from "./SolverSettingsForm";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const client = await activeClient();
  const stored = await loadSolverSettings(client.id);
  // Keyed by client, so switching clients starts a fresh form instead of carrying edits across.
  return <SolverSettingsForm key={client.id} clientName={client.name} initial={stored === null ? null : toView(stored)} />;
}
