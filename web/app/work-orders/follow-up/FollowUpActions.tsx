"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/app/portal/ui";
import styles from "./follow-up.module.css";

/** Marks a follow-up request contacted or resolved. A failure is shown beside the buttons and the row stays as it was. */
export default function FollowUpActions({ jobId }: { jobId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function update(action: "CONTACTED" | "RESOLVED") {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/dispatch/follow-up", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, action }) });
      if (!response.ok) throw new Error("Could not update follow-up");
      router.refresh();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "Could not update follow-up"); }
    finally { setBusy(false); }
  }
  return <>
    <Button variant="outlined" disabled={busy} onClick={() => void update("CONTACTED")}>Contacted</Button>
    <Button variant="outlined" disabled={busy} onClick={() => void update("RESOLVED")}>Resolved</Button>
    {error !== null && <p className={styles.error} role="alert">{error}</p>}
  </>;
}
