import { test } from "node:test";
import assert from "node:assert/strict";
import { connection, createBookingOffers, publicApiEnabled, SchedulerApiError, tokenVariable } from "./schedulerApi";

const TOKEN = `wfs_${"a".repeat(43)}`;
const code = (run: () => unknown) => {
  try { run(); } catch (error) { if (error instanceof SchedulerApiError) return error.code; throw error; }
  return "CONNECTED";
};

test("the public API is off unless explicitly turned on, and a typo is an error rather than off", () => {
  assert.equal(publicApiEnabled({}), false);
  assert.equal(publicApiEnabled({ SCHEDULER_PUBLIC_API: "false" }), false);
  assert.equal(publicApiEnabled({ SCHEDULER_PUBLIC_API: "true" }), true);
  assert.throws(() => publicApiEnabled({ SCHEDULER_PUBLIC_API: "yes" }));
});

test("each client's token comes from its own variable and is never shared or guessed", () => {
  assert.equal(tokenVariable("acme-water"), "SCHEDULER_API_TOKEN_ACME_WATER");
  const env = { SCHEDULER_API_URL: "http://scheduler-service:8000/", SCHEDULER_API_TOKEN_ACME: TOKEN };
  assert.deepEqual(connection("acme", env), { baseUrl: "http://scheduler-service:8000", token: TOKEN });
  assert.equal(code(() => connection("brook", env)), "NOT_CONNECTED", "Another client's token is not used");
  assert.equal(code(() => connection("acme", { ...env, SCHEDULER_API_TOKEN_ACME: "secret" })), "NOT_CONNECTED");
  assert.equal(code(() => connection("acme", { SCHEDULER_API_TOKEN_ACME: TOKEN })), "NOT_CONNECTED", "No URL");
  assert.equal(code(() => connection("acme", { ...env, SCHEDULER_API_URL: "http://scheduler.example.com" })), "NOT_CONNECTED",
    "A bearer token is never sent over plain http beyond the local network");
  assert.equal(code(() => connection("acme", { ...env, SCHEDULER_API_URL: "https://scheduler.example.com" })), "CONNECTED");
  assert.equal(code(() => connection("acme", { ...env, SCHEDULER_API_URL: "http://127.0.0.1:18000" })), "CONNECTED");
});

test("a token for another tenant is refused before any booking call, and API refusals keep their code", async () => {
  const original = globalThis.fetch;
  const saved = { url: process.env.SCHEDULER_API_URL, token: process.env.SCHEDULER_API_TOKEN_STUB_CLIENT };
  process.env.SCHEDULER_API_URL = "http://127.0.0.1:18999";
  process.env.SCHEDULER_API_TOKEN_STUB_CLIENT = TOKEN;
  const paths: string[] = [];
  try {
    globalThis.fetch = async input => { paths.push(new URL(String(input)).pathname); return Response.json({ tenantId: "someone-else" }); };
    const request = { requestId: "7d0e1c55-4c1e-4f7a-9d1a-0b6a3c1b2e10", offerLimit: 1, horizon: { firstDate: "2026-10-12", lastDate: "2026-10-12" },
      job: { id: "job", serviceId: "svc", durationMinutes: 60, location: { lat: 1, lng: 1 } },
      snapshot: { metroId: "m", timeZone: "America/Chicago", rates: { regularHourly: "1", overtimeHourly: "1", mileagePerMile: "1", travelBufferPct: "0", travelBufferMinutes: 0 },
        policy: { fairnessBudget: "0" }, technicians: [], technicianDays: [], appointments: [] } };
    await assert.rejects(createBookingOffers("stub-client", request), (error: unknown) => error instanceof SchedulerApiError && error.code === "NOT_CONNECTED");
    assert.deepEqual(paths, ["/api/v1/whoami"], "No booking call is made with a mismatched token");

    paths.length = 0;
    globalThis.fetch = async input => {
      const path = new URL(String(input)).pathname;
      paths.push(path);
      return path === "/api/v1/whoami" ? Response.json({ tenantId: "stub-client" })
        : Response.json({ error: "BUSY", message: "Search capacity exhausted" }, { status: 429 });
    };
    await assert.rejects(createBookingOffers("stub-client", request), (error: unknown) => error instanceof SchedulerApiError && error.code === "BUSY" && error.status === 429);
    globalThis.fetch = async () => Response.json({ offerSetId: "set", unexpected: true }, { status: 201 });
    await assert.rejects(createBookingOffers("stub-client", request), (error: unknown) => error instanceof SchedulerApiError && error.code === "MALFORMED");
  } finally {
    globalThis.fetch = original;
    if (saved.url === undefined) delete process.env.SCHEDULER_API_URL; else process.env.SCHEDULER_API_URL = saved.url;
    if (saved.token === undefined) delete process.env.SCHEDULER_API_TOKEN_STUB_CLIENT; else process.env.SCHEDULER_API_TOKEN_STUB_CLIENT = saved.token;
  }
});
