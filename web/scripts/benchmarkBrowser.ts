import assert from "node:assert/strict";
import { chromium } from "@playwright/test";
import { offersResponse, errorMessage } from "../lib/contracts";

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
      async search(jobId: string) {
        const measured = await page.evaluate(async id => {
          const started = performance.now();
          try {
            const response = await fetch("/api/book/refresh", { method: "POST", headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ jobId: id }), signal: AbortSignal.timeout(6000) });
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
