import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { STRICT_MUTATIONS } from "../src/cases.ts";
import { areaPoints, FIXTURE_POINTS, parseArea } from "../src/points.ts";
import { KIT_POLICY, SnapshotBuilder, UNLOCATABLE, versions } from "../src/snapshot.ts";

const LAST_MODIFIED = "2026-10-13T12:00:00.000Z";

function sample() {
  return new SnapshotBuilder("metro-omaha", "America/Chicago", "svc", LAST_MODIFIED)
    .day("tech-a", "2026-10-15", { lat: 41.25, lng: -95.93 }).day("tech-b", "2026-10-15", UNLOCATABLE)
    .visit("v1", "tech-a", "2026-10-15", { lat: 41.26, lng: -96.18 }, 0, 540).visit("v2", "tech-a", "2026-10-15", { lat: 41.27, lng: -96.0 }, 1, 660)
    .build();
}

test("a built snapshot carries every required field, with no default left out", () => {
  const snapshot = sample();
  assert.deepEqual(Object.keys(snapshot).sort(), ["appointments", "metroId", "policy", "rates", "technicianDays", "technicians", "timeZone"]);
  assert.deepEqual(Object.keys(snapshot.policy).sort(),
    ["bookingDeadlineMs", "costSearchSeconds", "dailySearch", "fairnessBudget", "fairnessSearchSeconds", "regularWindowThreshold"]);
  for (const day of snapshot.technicianDays) {
    assert.deepEqual(Object.keys(day).sort(), ["absences", "end", "lastModified", "maxPaidMinutes", "serviceDate", "shift", "start", "technicianId"]);
    assert.deepEqual(day.absences, []);
    assert.equal(day.lastModified, LAST_MODIFIED);
    assert.deepEqual(day.shift, { start: "2026-10-15T13:00:00.000Z", end: "2026-10-15T22:00:00.000Z" });
  }
  for (const appointment of snapshot.appointments)
    assert.deepEqual(Object.keys(appointment).sort(),
      ["durationMinutes", "id", "location", "plannedStart", "sequence", "serviceDate", "serviceId", "technicianId", "window"]);
  assert.equal(typeof snapshot.rates.regularHourly, "string", "Money is a decimal string");
  assert.equal(KIT_POLICY.costSearchSeconds + KIT_POLICY.fairnessSearchSeconds <= 240, true);
});

test("sequence and plannedStart agree, and each technician is listed once with the service", () => {
  const snapshot = sample();
  const ordered = snapshot.appointments.filter(item => item.technicianId === "tech-a").sort((a, b) => a.sequence - b.sequence);
  assert.ok(ordered.every((item, index) => index === 0 || Date.parse(item.plannedStart) > Date.parse(ordered[index - 1]?.plannedStart ?? "")));
  assert.deepEqual(snapshot.technicians, [{ id: "tech-a", qualifications: ["svc"] }, { id: "tech-b", qualifications: ["svc"] }]);
});

test("built snapshots do not share state", () => {
  const builder = new SnapshotBuilder("m", "UTC", "svc", LAST_MODIFIED).day("t", "2026-10-15", { lat: 1, lng: 2 });
  const first = builder.build();
  first.technicianDays[0]?.absences.push({ start: "x", end: "y" });
  first.policy.costSearchSeconds = 99;
  const second = builder.build();
  assert.deepEqual(second.technicianDays[0]?.absences, []);
  assert.equal(second.policy.costSearchSeconds, KIT_POLICY.costSearchSeconds);
});

test("commit versions name every technician-day with its lastModified", () => {
  assert.deepEqual(versions(sample()), [
    { technicianId: "tech-a", serviceDate: "2026-10-15", lastModified: LAST_MODIFIED },
    { technicianId: "tech-b", serviceDate: "2026-10-15", lastModified: LAST_MODIFIED },
  ]);
});

test("each strict-parsing mutation breaks exactly one rule and leaves the valid body intact", () => {
  const body = { requestId: "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21", serviceDate: "2026-10-15", snapshot: sample() };
  const original = structuredClone(body);
  const byName = new Map(STRICT_MUTATIONS.map(mutation => [mutation.name, mutation.mutate(body)]));
  assert.deepEqual(body, original, "Mutations work on copies");
  const duplicate = byName.get("duplicate key");
  assert.equal(typeof duplicate, "string");
  assert.ok(String(duplicate).startsWith('{"serviceDate":"2026-10-15","requestId":'), "The top-level key is sent first, then again");
  assert.equal(String(duplicate).length, JSON.stringify(body).length + '"serviceDate":"2026-10-15",'.length);
  assert.deepEqual(JSON.parse(String(duplicate)), body, "The duplicated body is otherwise the valid one");
  assert.equal((byName.get("hourly rate above its cap") as typeof body).snapshot.rates.overtimeHourly, "500.0001");
  assert.equal((byName.get("five-place mileage rate") as typeof body).snapshot.rates.mileagePerMile, "0.65555");
  const numeric = byName.get("numeric money") as typeof body;
  assert.equal(numeric.snapshot.rates.regularHourly as unknown, 30);
  assert.equal("plannedStart" in (byName.get("missing plannedStart") as typeof body).snapshot.appointments[0]!, false);
  assert.equal("absences" in (byName.get("missing absences") as typeof body).snapshot.technicianDays[0]!, false);
  assert.equal((byName.get("tenantId in the body") as Record<string, unknown>).tenantId, "another-tenant");
  assert.equal((byName.get("unknown field") as Record<string, unknown>).comment, "not in the contract");
});

test("fixture points are exactly the fixture router's Omaha points", () => {
  const router = readFileSync(new URL("../../fixture-routing.mjs", import.meta.url), "utf8");
  const known = [...router.matchAll(/\["(-?\d+\.\d{5}),(-?\d+\.\d{5})", (\d+)\]/g)].filter(match => Number(match[3]) >= 2).map(match => `${match[1]},${match[2]}`);
  assert.deepEqual(FIXTURE_POINTS.map(point => `${point.lat.toFixed(5)},${point.lng.toFixed(5)}`), known);
});

test("area points are distinct, repeatable and inside the box's margin", () => {
  const area = parseArea("41.20,-96.10,41.30,-95.95");
  const points = areaPoints(area, 6);
  assert.equal(new Set(points.map(point => `${point.lat},${point.lng}`)).size, 6);
  assert.deepEqual(areaPoints(area, 6), points);
  for (const point of points) {
    assert.ok(point.lat >= 41.21 && point.lat <= 41.29, `lat ${point.lat}`);
    assert.ok(point.lng >= -96.085 && point.lng <= -95.965, `lng ${point.lng}`);
  }
});

test("malformed areas are refused", () => {
  assert.throws(() => parseArea("41.2,-96.1,41.3"), /four numbers/);
  assert.throws(() => parseArea("41.3,-96.1,41.2,-95.9"), /south < north/);
  assert.throws(() => parseArea("41,-97,43,-95"), /more than one degree/);
  assert.throws(() => parseArea("91,-96.1,92,-95.9"), /outside/);
  assert.throws(() => parseArea("a,b,c,d"), /four numbers/);
});
