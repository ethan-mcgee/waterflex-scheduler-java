"use client";

import { useEffect, useState } from "react";
import { errorMessage } from "@/lib/contracts";
import { driveEndpoints, driveMinutes, scheduleDrive, visitDrives, type Endpoints, type ScheduleDrive, type VisitDrive } from "@/lib/scheduleDrive";

/** The day's drive times: not asked for (the weekly view), on their way, drawn, or unavailable with the reason. */
export type DriveState =
  | { status: "off" }
  | { status: "loading" }
  | { status: "ready"; drive: ScheduleDrive; visits: Map<string, VisitDrive>; minutes: Map<string, number>; endpoints: Endpoints }
  | { status: "error"; message: string };

/**
 * Loads the drive around every visit of the day on screen, again whenever the day's visits change (`version`). Until
 * the answer for the current day arrives the state is loading, worked out from the key rather than set, so an older
 * day's answer is never drawn on a newer day.
 */
export function useScheduleDrive(metroId: string, date: string, enabled: boolean, version: string): DriveState {
  const key = `${metroId}|${date}|${version}`;
  const [stored, setStored] = useState<{ key: string; state: DriveState } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    fetch(`/api/schedule/drive?metroId=${encodeURIComponent(metroId)}&date=${encodeURIComponent(date)}`, { signal: controller.signal })
      .then(async response => {
        const payload: unknown = await response.json().catch(() => undefined);
        if (!response.ok) throw new Error(errorMessage(payload, `Drive times could not be loaded (${response.status}).`));
        const parsed = scheduleDrive.safeParse(payload);
        if (!parsed.success || parsed.data.serviceDate !== date) throw new Error("The portal sent drive times this page cannot read.");
        const drive = parsed.data;
        setStored({ key, state: { status: "ready", drive, visits: visitDrives(drive), minutes: driveMinutes(drive), endpoints: driveEndpoints(drive) } });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setStored({ key, state: { status: "error", message: error instanceof Error ? error.message : "Drive times could not be loaded." } });
      });
    return () => controller.abort();
  }, [enabled, key, metroId, date]);
  if (!enabled) return { status: "off" };
  return stored !== null && stored.key === key ? stored.state : { status: "loading" };
}
