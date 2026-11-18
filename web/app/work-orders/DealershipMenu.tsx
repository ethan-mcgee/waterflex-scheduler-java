"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { Check, ChevronDown } from "lucide-react";
import type { ClientSummary } from "@/lib/clients";
import styles from "./shell.module.css";

/**
 * The top bar's dealership menu. In this portal it chooses the client the pages act for, through the same request as
 * the classic sidebar's client switcher, and it links back to the classic portal.
 */
export default function DealershipMenu({ clients, activeClientId }: { clients: ClientSummary[]; activeClientId: string }) {
  const router = useRouter();
  const menuId = useId();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const active = clients.find(client => client.id === activeClientId);
  if (active === undefined) throw new Error(`The active client ${activeClientId} is not in the client list`);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent) { if (event.key === "Escape") setOpen(false); return; }
      if (!(event.target instanceof Node) || !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", close); };
  }, [open]);

  async function choose(clientId: string) {
    setOpen(false);
    if (clientId === activeClientId) return;
    setPending(true);
    setError(null);
    try {
      const response = await fetch("/api/clients/active", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ clientId }) });
      if (!response.ok) { setError("Could not switch dealerships."); return; }
      // Metro and record IDs in the URL belong to the previous client, so the page starts fresh.
      router.push(window.location.pathname);
      router.refresh();
    } catch {
      setError("Could not switch dealerships.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className={styles.dealership} ref={root}>
      <button type="button" className={styles.dealershipButton} aria-haspopup="menu" aria-expanded={open} aria-controls={menuId}
        disabled={pending} onClick={() => setOpen(value => !value)} data-dealership-menu>
        <span className={styles.dealershipName}>{active.name}</span>
        <ChevronDown size={16} aria-hidden />
      </button>
      {open && <div className={styles.menu} id={menuId} role="menu" aria-label="Dealership">
        {clients.map(client => <button key={client.id} type="button" role="menuitemradio" aria-checked={client.id === activeClientId}
          className={styles.menuItem} onClick={() => void choose(client.id)}>
          <span>{client.name}</span>{client.id === activeClientId && <Check size={16} aria-hidden />}
        </button>)}
        <div className={styles.menuDivider} role="separator" />
        <Link role="menuitem" className={styles.menuItem} href="/schedule">Classic portal</Link>
      </div>}
      {error && <span className={styles.menuError} role="alert">{error}</span>}
    </div>
  );
}
