"use client";

import { useEffect, useState } from "react";
import { connectionReply, type ConnectionReply } from "@/lib/apiConnectionCore";
import styles from "./portal.module.css";

type View = { kind: "checking" } | { kind: "failed" } | { kind: "known"; reply: ConnectionReply };

const LABEL = { CONNECTED: "Scheduling API connected", NOT_CONNECTED: "Scheduling API not connected", UNREACHABLE: "Scheduling API unreachable" } as const;

/**
 * The top bar's scheduling API status: whether the active client can book and dispatch, so a missing token is visible
 * before anyone tries. The reason shows when the chip is hovered or focused.
 */
export default function ConnectionStatus({ activeClientId }: { activeClientId: string }) {
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

  const state = view.kind === "checking" ? "CHECKING" : view.kind === "failed" ? "UNKNOWN" : view.reply.state;
  const label = view.kind === "checking" ? "Checking scheduling API..." : view.kind === "failed" ? "Scheduling API status unknown" : LABEL[view.reply.state];
  const reply = view.kind === "known" && view.reply.state !== "CONNECTED" ? view.reply : null;
  return (
    <div className={styles.connection} data-connection-status data-state={state} role="status" tabIndex={reply ? 0 : undefined}>
      <span className={styles.connectionDot} aria-hidden />
      <span>{label}</span>
      {reply && <span className={styles.connectionDetail}>
        <small>{reply.reason}</small>
        {reply.state === "NOT_CONNECTED" && <small>Booking and dispatch are off for this client. See README, Public API tokens.</small>}
      </span>}
    </div>
  );
}
