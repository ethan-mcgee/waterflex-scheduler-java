import { activeClient } from "@/lib/activeClient";
import { toView } from "@/lib/clientSettings";
import { loadSolverSettings } from "@/lib/clientSettingsStore";
import { loadRunMinutes } from "@/lib/overnight";
import { timeOf } from "@/lib/overnightCore";
import { publicApiEnabled } from "@/lib/schedulerApi";
import SolverSettingsForm from "./SolverSettingsForm";
import OvernightSettings from "./OvernightSettings";

export const dynamic = "force-dynamic";

export default async function SettingsPage() {
  const client = await activeClient();
  const stored = await loadSolverSettings(client.id);
  // The portal runs overnight optimization only when it schedules through the public API.
  const overnight = publicApiEnabled() ? (await loadRunMinutes(client.id)).map(timeOf) : null;
  // Keyed by client, so switching clients starts a fresh form instead of carrying edits across.
  return <SolverSettingsForm key={client.id} clientName={client.name} initial={stored === null ? null : toView(stored)}
    overnight={overnight === null ? null : <OvernightSettings key={client.id} initialTimes={overnight} />} />;
}
