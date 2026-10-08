"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { ClientSummary } from "@/lib/clients";

/** Chooses the client the portal acts for. Every page and route reads the choice from the client cookie. */
export default function ClientSwitcher({ clients, activeClientId }: { clients: ClientSummary[]; activeClientId: string }) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function choose(clientId: string) {
    setPending(true);
    setError(null);
    try {
      const response = await fetch("/api/clients/active", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ clientId }) });
      if (!response.ok) { setError("Could not switch clients."); return; }
      // Metro and record IDs in the URL belong to the previous client, so start each section fresh.
      router.push(window.location.pathname);
      router.refresh();
    } catch {
      setError("Could not switch clients.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="sidebar-client">
      <label htmlFor="client-switcher">Client</label>
      <select id="client-switcher" value={activeClientId} disabled={pending || clients.length < 2}
        onChange={event => void choose(event.target.value)}>
        {clients.map(client => <option key={client.id} value={client.id}>{client.name}</option>)}
      </select>
      {error && <small role="alert">{error}</small>}
    </div>
  );
}
