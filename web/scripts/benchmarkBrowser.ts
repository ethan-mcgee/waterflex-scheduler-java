import assert from "node:assert/strict";
import { chromium } from "@playwright/test";
import { offersResponse, errorMessage, durableSearchStatus, appointmentSearch } from "../lib/contracts";
import { randomUUID } from "node:crypto";

export class BrowserSearchError extends Error {
  constructor(message: string, readonly elapsedMs: number) { super(message); }
}

/** Measures a real browser HTTP round trip through the portal for an already validated job. */
export async function browserBenchmark(portal: string) {
  const origin = new URL(portal);
  assert.ok(["localhost", "127.0.0.1"].includes(origin.hostname), "Browser benchmarks require a local isolated portal");
  assert.equal(origin.pathname, "/");
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const ready = await page.goto(`${origin.origin}/book`, { waitUntil: "networkidle" });
    assert.equal(ready?.status(), 200, "Benchmark portal must be healthy");
    return {
      version: browser.version(),
      close: () => browser.close(),
      async search(jobId: string, durable = false) {
        if (durable) {
          const started = performance.now();
          let requestId: string = randomUUID();
          const request = async (method: string) => {
            const measured = await page.evaluate(async ({ method, jobId, requestId }) => {
              const query = method === "POST" ? "" : `?id=${encodeURIComponent(requestId)}&jobId=${encodeURIComponent(jobId)}`;
              const response = await fetch(`/api/book/search${query}`, { method,
                headers: { "Content-Type": "application/json" },
                body: method === "POST" ? JSON.stringify({ jobId, requestId, refresh: false }) : undefined,
                signal: AbortSignal.timeout(12000) });
              const body: unknown = await response.json();
              return { status: response.status, body };
            }, { method, jobId, requestId });
            if (measured.status !== 200) throw new BrowserSearchError(errorMessage(measured.body, "Portal search failed"), performance.now() - started);
            return durableSearchStatus.parse(measured.body);
          };
          let status = await request("POST");
          requestId = status.id;
          const qualityTrace: Array<{ elapsedMs: number; costDeltaCents: number | null }> = [];
          while (status.state === "QUEUED" || status.state === "RUNNING") {
            if (performance.now() - started > 135000) {
              await request("DELETE");
              throw new BrowserSearchError("Durable browser search exceeded its bound", performance.now() - started);
            }
            await new Promise(resolve => setTimeout(resolve, 750));
            status = await request("GET");
            qualityTrace.push({ elapsedMs: performance.now() - started, costDeltaCents: status.bestCostDeltaCents ?? null });
          }
          return { elapsedMs: performance.now() - started, response: { jobId, offers: status.offers, qualityTrace,
            search: appointmentSearch.parse({ outcome: status.state === "AVAILABLE" ? "AVAILABLE" : status.state === "NO_CANDIDATE" ? "NO_CANDIDATE_FOUND" : "SEARCH_INCOMPLETE",
              prescribedSearchCompleted: status.state === "NO_CANDIDATE" || (status.state === "AVAILABLE" && status.stopReason === "COMPLETED"),
              elapsedMs: status.elapsedMs, queueMs: status.queueMs ?? undefined, retryable: status.state !== "AVAILABLE" && status.state !== "NO_CANDIDATE" }) } };
        }
        const measured = await page.evaluate(async id => {
          const started = performance.now();
          try {
            const response = await fetch("/api/book/refresh", { method: "POST", headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ jobId: id, deadlineEpochMs: Date.now() + 5000 }), signal: AbortSignal.timeout(6000) });
            const body: unknown = await response.json();
            return { status: response.status, body, elapsedMs: performance.now() - started, error: null };
          } catch (failure) {
            return { status: null, body: null, elapsedMs: performance.now() - started,
              error: failure instanceof Error ? failure.message : "Browser request failed" };
          }
        }, jobId);
        if (measured.status !== 200) throw new BrowserSearchError(measured.error ?? errorMessage(measured.body, "Portal search failed"), measured.elapsedMs);
        const parsed = offersResponse.safeParse(measured.body);
        if (!parsed.success) throw new BrowserSearchError("Malformed portal scheduling response", measured.elapsedMs);
        return { response: parsed.data, elapsedMs: measured.elapsedMs };
      },
    };
  } catch (failure) { await browser.close(); throw failure; }
}
