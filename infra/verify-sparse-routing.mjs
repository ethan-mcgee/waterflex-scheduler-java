// Direct real-road API check. This is not an appointment-search latency benchmark.
import assert from "node:assert/strict";
import { cpus, totalmem } from "node:os";
import { writeFile } from "node:fs/promises";

// routing-service requires its shared token on /internal endpoints; /health stays open.
const routingAuth = process.env.ROUTING_AUTH_TOKEN ? { Authorization: `Bearer ${process.env.ROUTING_AUTH_TOKEN}` } : {};

const base = process.argv[2] ?? "http://127.0.0.1:18003";
const health = await fetch(`${base}/health`).then(response => response.json());
assert.equal(health.ready, true);
assert.equal(typeof health.routingIdentity, "string");
const points = [{ lat: 41.2565, lng: -95.9345 }, { lat: 41.1544, lng: -96.0422 }, { lat: 41.2864, lng: -96.2345 }];
const pairs = points.flatMap((origin, from) => points.flatMap((destination, to) => from === to ? [] : [{ id: `${from}>${to}`, origin, destination }]));
async function post(path, body) {
  const started = performance.now();
  const response = await fetch(base + path, { method: "POST", headers: { "Content-Type": "application/json", ...routingAuth }, body: JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  const result = await response.json();
  assert.equal(response.status, 200, JSON.stringify(result));
  assert.equal(result.routingIdentity, health.routingIdentity);
  return { result, elapsedMs: performance.now() - started };
}
const sparse = await post("/internal/legs", { pairs, expectedRoutingIdentity: health.routingIdentity });
const repeated = await post("/internal/legs", { pairs, expectedRoutingIdentity: health.routingIdentity });
const full = await post("/internal/matrix", { origins: points, destinations: points, expectedRoutingIdentity: health.routingIdentity });
assert.deepEqual(repeated.result, sparse.result);
assert.equal(sparse.result.pairs.length, pairs.length);
for (const { id, leg } of sparse.result.pairs) {
  const [from, to] = id.split(">").map(Number);
  assert.deepEqual(leg, full.result.legs[from][to]);
  assert.equal(leg.routable, true);
}
const unreachable = await post("/internal/legs", { pairs: [{ id: "outside", origin: points[0], destination: { lat: 0, lng: 0 } }], expectedRoutingIdentity: health.routingIdentity });
assert.equal(unreachable.result.pairs[0].leg.routable, false);
const stale = await fetch(`${base}/internal/legs`, { method: "POST", headers: { "Content-Type": "application/json", ...routingAuth }, body: JSON.stringify({ pairs, expectedRoutingIdentity: "stale" }) });
assert.equal(stale.status, 409);
const report = {
  recordedAt: new Date().toISOString(), evidence: "Direct Omaha routing check only; not customer latency or fleet quality acceptance",
  hardware: { cpu: cpus()[0]?.model, logicalProcessors: cpus().length, memoryBytes: totalmem() },
  routing: health, points, directedPairs: sparse.result.pairs,
  apiSamplesMs: { initialSparse: sparse.elapsedMs, repeatedSparse: repeated.elapsedMs, subsequentFullMatrix: full.elapsedMs },
  checks: { sparseEqualsFull: true, repeatEqualsInitial: true, unreachableDistinct: true, staleIdentityRejected: true },
};
const json = JSON.stringify(report, null, 2) + "\n";
if (process.argv[3]) await writeFile(process.argv[3], json);
process.stdout.write(json);
