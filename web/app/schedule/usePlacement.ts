"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { z } from "zod";
import { errorMessage, placementPreview, placementSaved, type PlacementPreview, type PlacementSaved } from "@/lib/contracts";
import type { Spot } from "@/lib/placementRules";

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
  | { status: "ready"; preview: PlacementPreview; saving: boolean; saveError: string | null }
);

/** Turns a drop into a chosen place once the answer is known. */
export type DropResolver = (preview: PlacementPreview) => Chosen | null;

const subjectBody = (subject: PlacementSubject) => subject.kind === "job" ? { jobId: subject.id } : { appointmentId: subject.id };

/** Posts JSON and checks the answer against its contract; a refusal throws with the portal's own message. */
async function postJson<T>(path: string, body: unknown, contract: z.ZodType<T>, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal });
  const payload: unknown = await response.json().catch(() => undefined);
  if (!response.ok) throw new Error(errorMessage(payload, `The request failed (${response.status}).`));
  const parsed = contract.safeParse(payload);
  if (!parsed.success) throw new Error("The portal sent an answer this page cannot read. Reload the page and try again.");
  return parsed.data;
}

/**
 * Placing one job or moving one appointment on the schedule. Starting asks the portal once for the week; every hover,
 * drop and comparison after that is worked out on the page from that answer. Saving asks the portal to check again
 * with fresh facts and write. State changes only from events and from the answers, never directly in an effect.
 */
export function usePlacement(metroId: string, monday: string, onSaved: (saved: PlacementSaved, subject: PlacementSubject) => void) {
  const [stored, setStored] = useState<PlacementState | null>(null);
  const inFlight = useRef<AbortController | null>(null);
  /** A drop made before the answer arrived, applied when it does. */
  const pendingDrop = useRef<DropResolver | null>(null);
  const savedHandler = useRef(onSaved);
  useEffect(() => { savedHandler.current = onSaved; }, [onSaved]);

  /** Asks for a week's answer; the state changes only when it arrives. */
  const request = useCallback((subject: PlacementSubject, week: string) => {
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    postJson("/api/schedule/placement", { metroId, monday: week, ...subjectBody(subject) }, placementPreview, controller.signal)
      .then(preview => {
        if (controller.signal.aborted) return;
        const resolve = pendingDrop.current;
        pendingDrop.current = null;
        setStored(current => {
          if (current === null || current.subject.id !== subject.id) return current;
          const dropped = resolve === null ? null : resolve(preview);
          return { ...current, monday: week, chosen: dropped ?? current.chosen, status: "ready", preview, saving: false, saveError: null };
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        pendingDrop.current = null;
        setStored(current => current === null || current.subject.id !== subject.id ? current : { ...current, monday: week,
          status: "error", message: error instanceof Error ? error.message : "The places could not be found. Try again." });
      });
  }, [metroId]);

  /**
   * Starts placing a subject, unless it is the one already being placed for this week. `then` picks a place from the
   * answer as soon as it arrives, the way a drop made early does: a clicked job opens on its recommended place.
   */
  const start = useCallback((subject: PlacementSubject, then?: DropResolver) => {
    if (stored !== null && stored.subject.id === subject.id && stored.monday === monday && stored.status !== "error") return;
    pendingDrop.current = then ?? null;
    setStored({ subject, monday, chosen: null, acknowledged: false, status: "loading" });
    request(subject, monday);
  }, [monday, request, stored]);

  // Another week on screen needs that week's answer. Until it arrives the placement reads as loading (below).
  const stale = stored !== null && stored.monday !== monday;
  const staleSubject = stale ? stored.subject : null;
  useEffect(() => { if (staleSubject !== null) request(staleSubject, monday); }, [monday, request, staleSubject]);

  const placement = useMemo((): PlacementState | null => stored === null ? null
    : stored.monday !== monday ? { subject: stored.subject, monday, chosen: stored.chosen, acknowledged: false, status: "loading" } : stored,
  [monday, stored]);

  const choose = useCallback((spot: Spot, plannedStart: string | null) => {
    setStored(current => current === null ? current : { ...current, chosen: { spot, plannedStart }, acknowledged: false,
      ...(current.status === "ready" ? { saveError: null } : {}) });
  }, []);

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

  const cancel = useCallback(() => { inFlight.current?.abort(); pendingDrop.current = null; setStored(null); }, []);

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

  useEffect(() => () => inFlight.current?.abort(), []);

  return { placement, start, choose, drop, acknowledge, cancel, save };
}
