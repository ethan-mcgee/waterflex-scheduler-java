import { activeClient } from "@/lib/activeClient";
import { toView } from "@/lib/clientSettings";
import { loadSolverSettings } from "@/lib/clientSettingsStore";
import { loadRunMinutes } from "@/lib/overnight";
import { timeOf } from "@/lib/overnightCore";
import SolverSettingsForm from "./SolverSettingsForm";
import OvernightSettings from "./OvernightSettings";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const client = await activeClient();
  const stored = await loadSolverSettings(client.id);
  const overnight = (await loadRunMinutes(client.id)).map(timeOf);
  // Keyed by client, so switching clients starts a fresh form instead of carrying edits across.
  return <SolverSettingsForm key={client.id} clientName={client.name} initial={stored === null ? null : toView(stored)}
    overnight={<OvernightSettings key={client.id} initialTimes={overnight} />} />;
}
