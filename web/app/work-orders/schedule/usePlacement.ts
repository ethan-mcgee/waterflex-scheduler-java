"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";
import type { z } from "zod";
import { errorMessage, placementPreview, placementSaved, type PlacementPreview, type PlacementSaved } from "@/lib/contracts";
import { findGap, findStart, withTimedDays, type Spot } from "@/lib/placementRules";

/** What is being placed: a job waiting for the calendar, or an appointment being moved. `label` names it on screen. */
export type PlacementSubject = { kind: "job"; id: string; label: string } | { kind: "appointment"; id: string; label: string };

export type Chosen = { spot: Spot; plannedStart: string | null };

export type PlacementState = {
  subject: PlacementSubject;
  /** The week the preview covers; a preview is asked again when the page shows another week. */
  monday: string;
  chosen: Chosen | null;
  acknowledged: boolean;
} & (
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; preview: PlacementPreview; saving: boolean; saveError: string | null;
    /** The days whose stops the answer timed; the others keep their booked times until they are asked for. */
    timedDates: string[];
    /** Why a day paged to could not be timed; its visits keep their booked times. */
    timingError: string | null;
    /** Said after the schedule changed under the placement and its places were asked again. */
    notice: string | null }
);

/** Turns a drop into a chosen place once the answer is known. */
export type DropResolver = (preview: PlacementPreview) => Chosen | null;

const subjectBody = (subject: PlacementSubject) => subject.kind === "job" ? { jobId: subject.id } : { appointmentId: subject.id };
const keyOf = (subject: PlacementSubject, week: string, timed: readonly string[]) => `${subject.kind}:${subject.id}|${week}|${timed.join(",")}`;

/** Posts JSON and checks the answer against its contract; a refusal throws with the portal's own message. */
export async function postJson<T>(path: string, body: unknown, contract: z.ZodType<T>, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal });
  const payload: unknown = await response.json().catch(() => undefined);
  if (!response.ok) throw new Error(errorMessage(payload, `The request failed (${response.status}).`));
  const parsed = contract.safeParse(payload);
  if (!parsed.success) throw new Error("The portal sent an answer this page cannot read. Reload the page and try again.");
  return parsed.data;
}

/** A preview asked on a press, before it is known whether the press becomes a click or a drag. */
interface Prefetch { key: string; controller: AbortController; promise: Promise<PlacementPreview> }

/**
 * Placing one job or moving one appointment on the schedule. Starting asks the portal once for the week; every hover,
 * drop and comparison after that is worked out on the page from that answer. Only the days on screen (`shown`) come
 * with timed stops, which is most of an answer's size; a day paged to later is asked for alone and joined in.
 * Saving asks the portal to check again with fresh facts and write. State changes only from events and from the
 * answers, never directly in an effect.
 */
export function usePlacement(metroId: string, monday: string, shown: readonly string[],
  onSaved: (saved: PlacementSaved, subject: PlacementSubject) => void) {
  const [stored, setStored] = useState<PlacementState | null>(null);
  const inFlight = useRef<AbortController | null>(null);
  /** A preview asked on a press; the click or drag it becomes takes it over instead of asking again. */
  const prefetched = useRef<Prefetch | null>(null);
  /** The days being timed after a page to them; `failed` keeps a refused day from being asked again in a loop. */
  const filling = useRef<{ key: string; controller: AbortController; failed: boolean } | null>(null);
  /** A drop made before the answer arrived, applied when it does. */
  const pendingDrop = useRef<DropResolver | null>(null);
  const savedHandler = useRef(onSaved);
  useEffect(() => { savedHandler.current = onSaved; }, [onSaved]);
  // The dates on screen, stable while they stay the same days.
  const shownKey = shown.join(",");
  const shownDates = useMemo(() => shownKey.split(","), [shownKey]);

  const send = useCallback((subject: PlacementSubject, week: string, timedDates: readonly string[], withRestOfWeek: boolean, signal: AbortSignal) =>
    postJson("/api/schedule/placement", { metroId, monday: week, ...subjectBody(subject), timedDates, withRestOfWeek }, placementPreview, signal),
  [metroId]);

  /** Asks for a week's answer, or takes over the one a press already asked; the state changes only when it arrives. */
  const request = useCallback((subject: PlacementSubject, week: string, timedDates: readonly string[]) => {
    inFlight.current?.abort();
    const early = prefetched.current;
    let controller: AbortController, promise: Promise<PlacementPreview>;
    if (early !== null && early.key === keyOf(subject, week, timedDates)) {
      prefetched.current = null;
      ({ controller, promise } = early);
    } else {
      controller = new AbortController();
      promise = send(subject, week, timedDates, true, controller.signal);
    }
    inFlight.current = controller;
    promise
      .then(preview => {
        if (controller.signal.aborted) return;
        const resolve = pendingDrop.current;
        pendingDrop.current = null;
        setStored(current => {
          if (current === null || current.subject.id !== subject.id) return current;
          const dropped = resolve === null ? null : resolve(preview);
          return { ...current, monday: week, chosen: dropped ?? current.chosen, status: "ready", preview, saving: false, saveError: null,
            timedDates: [...timedDates], timingError: null, notice: null };
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        pendingDrop.current = null;
        setStored(current => current === null || current.subject.id !== subject.id ? current : { ...current, monday: week,
          status: "error", message: error instanceof Error ? error.message : "The places could not be found. Try again." });
      });
  }, [send]);

  /** True when this subject's answer for the week on screen is in, or on its way. */
  const placing = useCallback((subject: PlacementSubject) =>
    stored !== null && stored.subject.id === subject.id && stored.monday === monday && stored.status !== "error", [monday, stored]);

  /**
   * Starts placing a subject, unless it is the one already being placed for this week. `then` picks a place from the
   * answer as soon as it arrives, the way a drop made early does: a clicked job opens on its recommended place.
   */
  const start = useCallback((subject: PlacementSubject, then?: DropResolver) => {
    if (placing(subject)) return;
    pendingDrop.current = then ?? null;
    setStored({ subject, monday, chosen: null, acknowledged: false, status: "loading" });
    request(subject, monday, shownDates);
  }, [monday, placing, request, shownDates]);

  /**
   * Asks for a subject's answer as soon as its card is pressed, without showing anything: the click or drag the press
   * becomes takes the answer over, so the time the press takes is not spent waiting.
   */
  const prefetch = useCallback((subject: PlacementSubject) => {
    if (placing(subject)) return;
    const key = keyOf(subject, monday, shownDates);
    if (prefetched.current?.key === key) return;
    prefetched.current?.controller.abort();
    const controller = new AbortController();
    const promise = send(subject, monday, shownDates, true, controller.signal);
    // Its refusal is handled by the click or drag that takes it over; one dropped unused is not an error.
    promise.catch(() => undefined);
    prefetched.current = { key, controller, promise };
  }, [monday, placing, send, shownDates]);

  /** After a press ends: a preview it asked that no click or drag took over is dropped. */
  const settle = useCallback(() => { prefetched.current?.controller.abort(); prefetched.current = null; }, []);

  // Another week on screen needs that week's answer. Until it arrives the placement reads as loading (below).
  const stale = stored !== null && stored.monday !== monday;
  const staleSubject = stale ? stored.subject : null;
  useEffect(() => { if (staleSubject !== null) request(staleSubject, monday, shownDates); }, [monday, request, shownDates, staleSubject]);

  // A day on screen the answer did not time (paged to while placing) is asked for alone and joined in. Until then the
  // placement stays ready: places, drive and options come from the untimed answer, and that day's visits keep their
  // booked times.
  const readyNow = stored !== null && stored.status === "ready" && stored.monday === monday ? stored : null;
  const missingKey = readyNow === null ? "" : shownDates.filter(day => !readyNow.timedDates.includes(day)).join(",");
  const fillSubject = missingKey === "" || readyNow === null ? null : readyNow.subject;
  const identity = readyNow === null ? null : readyNow.preview.answer.routingIdentity;
  useEffect(() => {
    if (fillSubject === null) {
      // Nothing missing: a day that failed is asked again when it is paged back to.
      if (filling.current?.failed) filling.current = null;
      return;
    }
    const key = keyOf(fillSubject, monday, [missingKey]);
    if (filling.current?.key === key) return;
    filling.current?.controller.abort();
    const controller = new AbortController();
    filling.current = { key, controller, failed: false };
    const dates = missingKey.split(",");
    send(fillSubject, monday, dates, false, controller.signal)
      .then(extra => {
        if (controller.signal.aborted) return;
        filling.current = null;
        // Answers from two road maps cannot be joined; the whole week is asked again instead.
        if (extra.answer.routingIdentity !== identity) { request(fillSubject, monday, shownDates); return; }
        setStored(current => {
          if (current === null || current.status !== "ready" || current.subject.id !== fillSubject.id || current.monday !== monday) return current;
          const answer = withTimedDays(current.preview.answer, extra.answer, dates);
          return answer === null ? current : { ...current, preview: { ...current.preview, answer },
            timedDates: [...new Set([...current.timedDates, ...dates])], timingError: null };
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (filling.current !== null) filling.current.failed = true;
        setStored(current => current === null || current.status !== "ready" || current.subject.id !== fillSubject.id ? current
          : { ...current, timingError: error instanceof Error ? error.message : "This day's visits could not be re-timed." });
      });
  }, [fillSubject, identity, missingKey, monday, request, send, shownDates]);

  const placement = useMemo((): PlacementState | null => stored === null ? null
    : stored.monday !== monday ? { subject: stored.subject, monday, chosen: stored.chosen, acknowledged: false, status: "loading" } : stored,
  [monday, stored]);

  const choose = useCallback((spot: Spot, plannedStart: string | null) => {
    setStored(current => current === null ? current : { ...current, chosen: { spot, plannedStart }, acknowledged: false,
      ...(current.status === "ready" ? { saveError: null, notice: null } : {}) });
  }, []);

  /**
   * Asks the week again for the placement on screen, after the schedule changed underneath it. The panel stays as it
   * is until the answer arrives. A chosen place the new answer still offers is kept; one that is gone is cleared and
   * said. A refusal (the visit was cancelled, or someone else placed the job) is shown in its place.
   */
  const refresh = useCallback(() => {
    if (stored === null || stored.status !== "ready" || stored.monday !== monday || stored.saving) return;
    const { subject } = stored;
    inFlight.current?.abort();
    filling.current?.controller.abort();
    filling.current = null;
    const controller = new AbortController();
    inFlight.current = controller;
    send(subject, monday, shownDates, true, controller.signal)
      .then(preview => {
        if (controller.signal.aborted) return;
        setStored(current => {
          if (current === null || current.status !== "ready" || current.subject.id !== subject.id) return current;
          const { chosen } = current;
          const found = chosen === null ? undefined : findGap(preview.answer, chosen.spot);
          const holds = chosen !== null && found !== undefined && (chosen.plannedStart === null || findStart(found.gap, chosen.plannedStart) !== undefined);
          return { ...current, preview, timedDates: [...shownDates], timingError: null, chosen: holds ? chosen : null,
            acknowledged: holds && current.acknowledged, saveError: null,
            notice: chosen !== null && !holds ? "The schedule changed and that place is gone. Pick again." : "The schedule changed. These places are up to date." };
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setStored(current => current === null || current.subject.id !== subject.id ? current
          : { subject: current.subject, monday: current.monday, chosen: null, acknowledged: false, status: "error",
            message: error instanceof Error ? error.message : "The places could not be found. Try again." });
      });
  }, [monday, send, shownDates, stored]);

  /** A drop: chosen now when the answer is in, otherwise as soon as it arrives. */
  const drop = useCallback((resolve: DropResolver) => {
    if (placement !== null && placement.status === "ready") {
      const chosen = resolve(placement.preview);
      if (chosen !== null) choose(chosen.spot, chosen.plannedStart);
      return;
    }
    pendingDrop.current = resolve;
  }, [choose, placement]);

  const acknowledge = useCallback((acknowledged: boolean) => {
    setStored(current => current === null ? current : { ...current, acknowledged });
  }, []);

  const abortAll = useCallback(() => {
    inFlight.current?.abort();
    filling.current?.controller.abort();
    filling.current = null;
    settle();
  }, [settle]);

  const cancel = useCallback(() => { abortAll(); pendingDrop.current = null; setStored(null); }, [abortAll]);

  const save = useCallback(() => {
    if (placement === null || placement.status !== "ready" || placement.chosen === null || placement.chosen.plannedStart === null) return;
    const { subject, chosen, acknowledged } = placement;
    setStored({ ...placement, saving: true, saveError: null });
    postJson("/api/schedule/placement/save", { metroId, ...subjectBody(subject), spot: chosen.spot, plannedStart: chosen.plannedStart, acknowledged },
      placementSaved)
      .then(saved => { setStored(null); savedHandler.current(saved, subject); })
      .catch((error: unknown) => setStored(current => current === null || current.status !== "ready" ? current
        : { ...current, saving: false, saveError: error instanceof Error ? error.message : "The placement could not be saved. Try again." }));
  }, [metroId, placement]);

  useAbortOnUnmount(abortAll);

  return { placement, start, prefetch, settle, refresh, choose, drop, acknowledge, cancel, save };
}
