"use client";

import { useEffect, useState } from "react";
import { connectionReply, type ConnectionReply } from "@/lib/apiConnectionCore";

type View = { kind: "checking" } | { kind: "failed" } | { kind: "known"; reply: ConnectionReply };

const LABEL = { CONNECTED: "Scheduling API connected", NOT_CONNECTED: "Scheduling API not connected", UNREACHABLE: "Scheduling API unreachable" } as const;

/** Shows whether the active client can book and dispatch, so a missing token is visible before anyone tries. */
export default function ConnectionBadge({ activeClientId }: { activeClientId: string }) {
  // The status is stored with the client it was fetched for, so a client switch shows "checking" without a reset.
  const [fetched, setFetched] = useState<{ clientId: string; view: View } | null>(null);
  const view: View = fetched !== null && fetched.clientId === activeClientId ? fetched.view : { kind: "checking" };

  useEffect(() => {
    let current = true;
    fetch("/api/clients/connection", { cache: "no-store" })
      .then(async response => {
        const parsed = connectionReply.safeParse(response.ok ? await response.json() : null);
        if (!current) return;
        // A reply for another client (the cookie changed meanwhile) is not this client's status.
        setFetched({ clientId: activeClientId, view: parsed.success && parsed.data.clientId === activeClientId ? { kind: "known", reply: parsed.data } : { kind: "failed" } });
      })
      .catch(() => { if (current) setFetched({ clientId: activeClientId, view: { kind: "failed" } }); });
    return () => { current = false; };
  }, [activeClientId]);

  if (view.kind === "checking") return <div className="sidebar-connection" data-state="CHECKING" role="status">Checking scheduling API...</div>;
  if (view.kind === "failed") return <div className="sidebar-connection" data-state="UNKNOWN" role="status">Scheduling API status unknown</div>;
  const { reply } = view;
  return (
    <div className="sidebar-connection" data-state={reply.state} role="status">
      <span>{LABEL[reply.state]}</span>
      {reply.state !== "CONNECTED" && <small title={reply.reason}>{reply.reason}</small>}
      {reply.state === "NOT_CONNECTED" && <small>Booking and dispatch are off for this client. See README, Public API tokens.</small>}
    </div>
  );
}
