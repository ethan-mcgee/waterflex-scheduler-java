"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export default function FollowUpActions({ jobId }: { jobId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function update(action: "CONTACTED" | "RESOLVED") {
    setBusy(true);
    try {
      const response = await fetch("/api/dispatch/follow-up", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ jobId, action }) });
      if (!response.ok) throw new Error("Could not update follow-up");
      router.refresh();
    } catch (error) { window.alert(error instanceof Error ? error.message : "Could not update follow-up"); }
    finally { setBusy(false); }
  }
  return <><button disabled={busy} onClick={() => update("CONTACTED")}>Contacted</button> <button disabled={busy} onClick={() => update("RESOLVED")}>Resolved</button></>;
}
