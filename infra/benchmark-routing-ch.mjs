// Direct GraphHopper comparison. No appointment-search or booking-capacity claims.
import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import { cpus, totalmem } from "node:os";
import { createHash } from "node:crypto";

const [flexibleUrl, preparedUrl, output, revision] = process.argv.slice(2);
assert.ok(flexibleUrl && preparedUrl && output && revision, "Pass flexible URL, CH URL, new output file and exact revision");
const points = [
  [41.2565, -95.9345], [41.1544, -96.0422], [41.2864, -96.2345], [41.2603, -96.0731],
  [41.3047, -95.9940], [41.2058, -96.1187], [41.1820, -95.9530], [41.2650, -95.9990],
  [41.2350, -96.0420], [41.3000, -96.1200], [41.2590, -95.8600], [41.2100, -96.1800],
].map(([lat, lng]) => ({ lat, lng }));
const pairs = points.flatMap((origin, from) => points.flatMap((destination, to) => from === to ? [] : [{ id: `${from}>${to}`, origin, destination }]));
const engines = await Promise.all([flexibleUrl, preparedUrl].map(async url => {
  const response = await fetch(`${url}/health`, { signal: AbortSignal.timeout(10000) }); assert.equal(response.status, 200);
  const health = await response.json(); assert.equal(health.ready, true); assert.equal(health.engineVersion, "11.0");
  assert.equal(typeof health.routingIdentity, "string"); return { url, health };
}));
assert.equal(engines[0].health.preparedConfiguration, "flexible");
assert.equal(engines[1].health.preparedConfiguration, "CH-car-v1");
assert.notEqual(engines[0].health.routingIdentity, engines[1].health.routingIdentity);
assert.equal(engines[0].health.mapVersion, engines[1].health.mapVersion);
async function request(engine, path, body) {
  const started = performance.now();
  const response = await fetch(engine.url + path, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...body, expectedRoutingIdentity: engine.health.routingIdentity }), signal: AbortSignal.timeout(30000) });
  const value = await response.json(); assert.equal(response.status, 200, JSON.stringify(value));
  assert.equal(value.routingIdentity, engine.health.routingIdentity);
  return { value, ms: performance.now() - started };
}
const first = [];
for (const engine of engines) first.push(await request(engine, "/internal/legs", { pairs }));
const differences = []; let routable = 0;
for (let i = 0; i < pairs.length; i++) {
  const a = first[0].value.pairs[i], b = first[1].value.pairs[i];
  assert.equal(a.id, pairs[i].id); assert.equal(b.id, pairs[i].id);
  assert.equal(a.leg.routable, b.leg.routable, a.id);
  if (a.leg.routable) {
    routable++;
    if (a.leg.seconds !== b.leg.seconds || a.leg.meters !== b.leg.meters)
      differences.push({ id: a.id, flexible: a.leg, prepared: b.leg });
  }
}
// Record differences instead of hiding alternate equally weighted paths. Exact parity is a conservative enablement gate.
for (const engine of engines) {
  const full = await request(engine, "/internal/matrix", { origins: points, destinations: points });
  const sparse = first[engines.indexOf(engine)].value.pairs;
  for (const pair of sparse) { const [from, to] = pair.id.split(">").map(Number); assert.deepEqual(pair.leg, full.value.legs[from][to]); }
  const outside = await request(engine, "/internal/legs", { pairs: [{ id: "outside", origin: points[0], destination: { lat: 0, lng: 0 } }] });
  assert.equal(outside.value.pairs[0].leg.routable, false);
  const stale = await fetch(engine.url + "/internal/legs", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pairs: [pairs[0]], expectedRoutingIdentity: "stale" }), signal: AbortSignal.timeout(10000) });
  assert.equal(stale.status, 409);
}
const latency = [];
for (const concurrency of [1, 5, 10]) {
  const samples = [[], []];
  for (let round = 0; round < 10; round++) {
    for (const index of round % 2 ? [1, 0] : [0, 1]) {
      const batch = await Promise.all(Array.from({ length: concurrency }, (_, requestIndex) => {
        const start = (round * 17 + requestIndex * 11) % pairs.length;
        const selected = Array.from({ length: 16 }, (_, offset) => pairs[(start + offset) % pairs.length]);
        return request(engines[index], "/internal/legs", { pairs: selected });
      }));
      samples[index].push(...batch.map(sample => sample.ms));
    }
  }
  for (let index = 0; index < engines.length; index++) {
    const ordered = samples[index].toSorted((a, b) => a - b);
    const percentile = p => ordered[Math.min(ordered.length - 1, Math.ceil(p * ordered.length) - 1)];
    latency.push({ engine: engines[index].health.preparedConfiguration, concurrency, pairsPerRequest: 16,
      count: ordered.length, p50Ms: percentile(.5), p95Ms: percentile(.95), p99Ms: percentile(.99), samplesMs: samples[index] });
  }
}
const report = { revision, recordedAt: new Date().toISOString(), evidence: "Omaha road-routing API comparison, not appointment-search acceptance",
  hardware: { cpu: cpus()[0].model, logicalProcessors: cpus().length, memoryBytes: totalmem() },
  engines: engines.map(engine => engine.health), points, datasetFingerprint: createHash("sha256").update(JSON.stringify(pairs)).digest("hex"),
  pairs: pairs.length, routable, firstRequestMs: first.map(sample => sample.ms), differences, latency,
  checks: { exactDirectedParity: differences.length === 0, sparseEqualsFull: true, outsideUnroutable: true, staleIdentityRejected: true },
  cache: "Both instances must be launched with routing.cache.max-entries=1; warm JVM, effectively uncached multi-leg requests",
};
await writeFile(output, JSON.stringify(report, null, 2) + "\n", { flag: "wx" });
console.log(JSON.stringify({ output, checks: report.checks, pairs: pairs.length, routable, latency: latency.map(({ samplesMs, ...value }) => value) }, null, 2));
if (differences.length) process.exitCode = 2;
