"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { z } from "zod";
import { bookingFailure, errorMessage, offersResponse, selection, type offer } from "@/lib/contracts";
import { appointmentSearchMessage } from "@/lib/appointmentSearch";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";

export type JobOffer = z.infer<typeof offer>;

/**
 * The booking service's recommended times for one waiting job: searching, the offers it holds (each until its
 * `expiresAt`), or why there are none. `booking` is the offer being booked; `error` says why the last step failed.
 */
export type JobOffers =
  | { jobId: string; status: "searching" }
  | { jobId: string; status: "ready"; offers: JobOffer[]; message: string | null; booking: string | null; error: string | null }
  | { jobId: string; status: "failed"; message: string };

const post = (path: string, body: unknown, signal?: AbortSignal) =>
  fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal });

/**
 * Asks the booking service for a waiting job's recommended times, the same search the booking page runs under the
 * dealer's solver settings (offer count, window length, horizon from tomorrow). Booking one selects and confirms its
 * hold; releasing gives every held time back. A newer search for any job replaces the one still waiting.
 */
export function useJobOffers() {
  const [state, setState] = useState<JobOffers | null>(null);
  // The offers as last drawn, read by events that give held times back; never read during render.
  const latest = useRef<JobOffers | null>(null);
  useEffect(() => { latest.current = state; }, [state]);
  const inFlight = useRef<AbortController | null>(null);
  // The held offers' expiry is shown as a countdown; it ticks only while offers are on screen.
  const [now, setNow] = useState(() => Date.now());
  const holding = state !== null && state.status === "ready" && state.offers.length > 0;
  useEffect(() => {
    if (!holding) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [holding]);

  /** Gives back every time the job's current offers hold. A failure is only logged: holds expire on their own. */
  const releaseHeld = useCallback((current: JobOffers | null) => {
    if (current === null || current.status !== "ready") return;
    const first = current.offers[0];
    if (first === undefined) return;
    void post("/api/book/release", { jobId: current.jobId, offerId: first.offerId })
      .then(async response => { if (!response.ok) console.warn("Releasing held times failed", response.status, await response.text()); })
      .catch((error: unknown) => console.warn("Releasing held times failed", error));
  }, []);

  const search = useCallback((jobId: string) => {
    inFlight.current?.abort();
    const controller = new AbortController();
    inFlight.current = controller;
    // Another job's held times are given back; the same job's are ended by the new search itself.
    if (latest.current !== null && latest.current.jobId !== jobId) releaseHeld(latest.current);
    latest.current = { jobId, status: "searching" };
    setState(latest.current);
    setNow(Date.now());
    post("/api/book/refresh", { jobId }, controller.signal)
      .then(async response => {
        const payload: unknown = await response.json().catch(() => undefined);
        if (!response.ok) throw new Error(errorMessage(payload, "The booking service could not be reached. Try again."));
        const parsed = offersResponse.safeParse(payload);
        if (!parsed.success) throw new Error("The portal sent an answer this page cannot read. Reload the page and try again.");
        return parsed.data;
      })
      .then(found => {
        if (controller.signal.aborted) return;
        setNow(Date.now());
        setState(current => current?.jobId !== jobId ? current
          : { jobId, status: "ready", offers: found.offers, message: found.search.outcome === "NO_CANDIDATE_FOUND"
            ? "The booking service found no time within the dealer's booking horizon. Place it yourself below." : appointmentSearchMessage(found.search),
            booking: null, error: null });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setState(current => current?.jobId !== jobId ? current
          : { jobId, status: "failed", message: error instanceof Error ? error.message : "The booking service could not be reached. Try again." });
      });
  }, [releaseHeld]);

  /** Books an offer; resolves with what was booked, or null when it was not (the card then says why). */
  const book = useCallback((offerId: string): Promise<{ jobId: string; offer: JobOffer } | null> => {
    if (state === null || state.status !== "ready" || state.booking !== null) return Promise.resolve(null);
    const chosen = state.offers.find(item => item.offerId === offerId);
    if (chosen === undefined) return Promise.resolve(null);
    const { jobId } = state;
    setState({ ...state, booking: offerId, error: null });
    return post("/api/book/select", { jobId, offerId })
      .then(async response => {
        const payload: unknown = await response.json().catch(() => undefined);
        if (response.ok) {
          if (!selection.safeParse(payload).success) throw new Error("The portal sent an answer this page cannot read. Reload the page and try again.");
          latest.current = null;
          setState(null);
          return { jobId, offer: chosen };
        }
        // The schedule changed while booking: the portal searched again and sends the current times with its reason.
        const failed = bookingFailure.safeParse(payload);
        const message = failed.success && failed.data.error !== undefined ? failed.data.error : errorMessage(payload, "That time could not be booked. Search again.");
        setState(current => current === null || current.jobId !== jobId || current.status !== "ready" ? current
          : { ...current, booking: null, error: message,
            ...(failed.success && failed.data.offers !== undefined ? { offers: failed.data.offers } : {}) });
        return null;
      })
      .catch((error: unknown) => {
        setState(current => current === null || current.jobId !== jobId || current.status !== "ready" ? current
          : { ...current, booking: null, error: error instanceof Error ? error.message : "That time could not be booked. Search again." });
        return null;
      });
  }, [state]);

  /** Gives back the held times and forgets the job's offers: the card closed, a visit was opened, or it was placed by hand. */
  const release = useCallback(() => {
    inFlight.current?.abort();
    releaseHeld(latest.current);
    latest.current = null;
    setState(null);
  }, [releaseHeld]);

  useAbortOnUnmount(useCallback(() => inFlight.current?.abort(), []));

  /** Searches for a job unless its times are already on screen or on their way, as when its card is dragged again. */
  const open = useCallback((jobId: string) => {
    if (latest.current !== null && latest.current.jobId === jobId && latest.current.status !== "failed") return;
    search(jobId);
  }, [search]);

  return { offers: state, now, open, search, book, release };
}

