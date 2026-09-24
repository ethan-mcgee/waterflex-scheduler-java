import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { benchmarkDiagnostics } from "./benchmarkDiagnostics";

const valid = { format: 1, completed: true, stopReason: "COMPLETED", configurationFingerprint: "policy",
  routingIdentity: "fixture", capturedAt: "2026-09-24T08:00:00Z", snapshotAgeMs: 1, routingPairs: 2,
  evaluatedRoutes: 3, reusedRoutes: 4, prunedArrangements: 5, optionalRefinementMillis: 250,
  distinctRegularWindows: 2, confirmedRegularMinutes: 90, regularCapacityMinutes: 100,
  overtimeAuthorized: true, limits: { routes: 6, depth: 2, beam: 8, arrangementsPerWindow: 500 },
  coverage: [], offerSources: { offer: "INSERTION" } };

test("diagnostics preserve retries and explicitly identify unavailable jobs", async () => {
  const directory = await mkdtemp(join(tmpdir(), "waterflex-diagnostics-"));
  try {
    const path = join(directory, "server.log");
    const line = `INFO Booking search diagnostics for job requested: ${JSON.stringify(valid)}`;
    await writeFile(path, `${line}\n${line}\nINFO Booking search diagnostics for job unrelated: null\n`);
    const result = await benchmarkDiagnostics(path, ["requested", "missing"]);
    assert.equal(result?.attempts.length, 2);
    assert.deepEqual(result?.unavailableJobIds, ["missing"]);
    assert.equal(await benchmarkDiagnostics(undefined, ["requested"]), null);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("malformed required diagnostic values fail instead of becoming zero", async () => {
  const directory = await mkdtemp(join(tmpdir(), "waterflex-diagnostics-"));
  try {
    const path = join(directory, "server.log");
    for (const routingPairs of [null, -1, "2"]) {
      await writeFile(path, `INFO Booking search diagnostics for job requested: ${JSON.stringify({ ...valid, routingPairs })}\n`);
      await assert.rejects(benchmarkDiagnostics(path, ["requested"]));
    }
    for (const body of ["null", "{broken"]) {
      await writeFile(path, `INFO Booking search diagnostics for job requested: ${body}\n`);
      await assert.rejects(benchmarkDiagnostics(path, ["requested"]));
    }
  } finally { await rm(directory, { recursive: true, force: true }); }
});
