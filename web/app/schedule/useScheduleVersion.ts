"use client";

import { useEffect, useRef } from "react";
import { scheduleVersionAnswer } from "@/lib/contracts";

/** How often the page asks whether the schedule changed while its tab is in view. */
const CHECK_EVERY_MS = 20_000;

/**
 * Tells the page when the schedule it shows has changed underneath it. It asks the portal for the week's version every
 * twenty seconds while the tab is in view, and at once when the tab is returned to, and calls `onChange` when the
 * answer differs from `version`, the one the page was rendered with. A check that fails is skipped: the page keeps
 * working and the next check tries again.
 *
 * Only the timer below is polling. A push channel (server-sent events fed by the database) can call the same check,
 * or `onChange` directly, without the page changing.
 */
export function useScheduleVersion(metroId: string, monday: string, version: string, onChange: () => void) {
  const latest = useRef({ version, onChange });
  useEffect(() => { latest.current = { version, onChange }; }, [version, onChange]);

  useEffect(() => {
    let checking = false, stopped = false;
    const controller = new AbortController();
    const check = () => {
      if (checking || stopped || document.visibilityState !== "visible") return;
      checking = true;
      fetch(`/api/schedule/version?metroId=${encodeURIComponent(metroId)}&monday=${monday}`, { signal: controller.signal, cache: "no-store" })
        .then(async response => {
          if (!response.ok) return;
          const parsed = scheduleVersionAnswer.safeParse(await response.json());
          if (parsed.success && !stopped && parsed.data.version !== latest.current.version) latest.current.onChange();
        })
        .catch(() => undefined)
        .finally(() => { checking = false; });
    };
    const timer = setInterval(check, CHECK_EVERY_MS);
    document.addEventListener("visibilitychange", check);
    window.addEventListener("focus", check);
    return () => {
      stopped = true;
      controller.abort();
      clearInterval(timer);
      document.removeEventListener("visibilitychange", check);
      window.removeEventListener("focus", check);
    };
  }, [metroId, monday]);
}
