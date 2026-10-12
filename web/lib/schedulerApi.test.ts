import { test } from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { connection, createBookingOffers, proposalTimeoutMs, SchedulerApiError, tokenVariable, verifyTenant } from "./schedulerApi";
import { ABANDON_AFTER_MINUTES } from "./overnightCore";

const TOKEN = `wfs_${"a".repeat(43)}`;
const code = (run: () => unknown) => {
  try { run(); } catch (error) { if (error instanceof SchedulerApiError) return error.code; throw error; }
  return "CONNECTED";
};

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
    const request = { requestId: "7d0e1c55-4c1e-4f7a-9d1a-0b6a3c1b2e10", offerLimit: 1, windowHours: 4, horizon: { firstDate: "2026-10-12", lastDate: "2026-10-12" },
      job: { id: "job", serviceId: "svc", durationMinutes: 60, location: { lat: 1, lng: 1 } },
      snapshot: { metroId: "m", timeZone: "America/Chicago", rates: { regularHourly: "1", overtimeHourly: "1", mileagePerMile: "1", travelBufferPct: "0", travelBufferMinutes: 0 },
        policy: { fairnessBudget: "0", dailySearch: "TABU_KOPT" as const, costSearchSeconds: 10, fairnessSearchSeconds: 5 }, technicians: [], technicianDays: [], appointments: [] } };
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

test("a client's token file is read only when its variable is unset, and a missing or bad file never connects", () => {
  const env = { SCHEDULER_API_URL: "http://scheduler-service:8000", SCHEDULER_API_TOKEN_DIR: "/run/tokens" };
  const files = new Map<string, string>([[join("/run/tokens", "acme"), `${TOKEN}\n`], [join("/run/tokens", "bad"), "secret"],
    [join("/run/tokens", "empty"), ""]]);
  const read: string[] = [];
  const reader = (path: string) => { read.push(path); return files.get(path) ?? null; };
  assert.deepEqual(connection("acme", env, reader), { baseUrl: "http://scheduler-service:8000", token: TOKEN }, "File token, trailing newline trimmed");
  const other = `wfs_${"b".repeat(43)}`;
  read.length = 0;
  assert.equal(connection("acme", { ...env, SCHEDULER_API_TOKEN_ACME: other }, reader).token, other, "The variable wins");
  assert.deepEqual(read, [], "The file is not read when the variable is set");
  assert.equal(code(() => connection("brook", env, reader)), "NOT_CONNECTED", "No file for this client; another client's file is not used");
  assert.deepEqual(read, [join("/run/tokens", "brook")]);
  assert.equal(code(() => connection("bad", env, reader)), "NOT_CONNECTED", "Malformed token in the file");
  assert.equal(code(() => connection("empty", env, reader)), "NOT_CONNECTED", "Empty file");
  assert.equal(code(() => connection("acme", env, () => { throw new Error("EACCES"); })), "NOT_CONNECTED", "Unreadable file");
  read.length = 0;
  assert.equal(code(() => connection("../acme", env, reader)), "NOT_CONNECTED", "A client ID is never used as a path outside the directory");
  assert.deepEqual(read, []);
  assert.equal(code(() => connection("acme", { SCHEDULER_API_URL: env.SCHEDULER_API_URL }, reader)), "NOT_CONNECTED", "No directory configured");
  try { connection("bad", env, reader); } catch (error) {
    assert.ok(error instanceof SchedulerApiError && !error.message.includes("secret"), "The message never repeats file contents");
  }
});

test("a token the scheduling service does not accept is not connected, not a malformed reply", async () => {
  const original = globalThis.fetch;
  const saved = { url: process.env.SCHEDULER_API_URL, token: process.env.SCHEDULER_API_TOKEN_REVOKED_CLIENT };
  process.env.SCHEDULER_API_URL = "http://127.0.0.1:18999";
  process.env.SCHEDULER_API_TOKEN_REVOKED_CLIENT = TOKEN;
  try {
    globalThis.fetch = async () => new Response("<html>Unauthorized</html>", { status: 401, headers: { "Content-Type": "text/html" } });
    await assert.rejects(verifyTenant("revoked-client"), (error: unknown) => error instanceof SchedulerApiError && error.code === "NOT_CONNECTED");
  } finally {
    globalThis.fetch = original;
    if (saved.url === undefined) delete process.env.SCHEDULER_API_URL; else process.env.SCHEDULER_API_URL = saved.url;
    if (saved.token === undefined) delete process.env.SCHEDULER_API_TOKEN_REVOKED_CLIENT; else process.env.SCHEDULER_API_TOKEN_REVOKED_CLIENT = saved.token;
  }
});

test("a proposal waits for the client's whole search, and an overnight day never outlasts the abandon window", () => {
  const policy = (cost: number, fairness: number) => ({ fairnessBudget: "0", dailySearch: "TABU_KOPT" as const, costSearchSeconds: cost, fairnessSearchSeconds: fairness });
  assert.equal(proposalTimeoutMs(policy(10, 5)), 30000, "The earlier fixed 15 seconds of search keeps its 30-second wait");
  assert.equal(proposalTimeoutMs(policy(240, 0)), 255000);
  assert.ok(proposalTimeoutMs(policy(200, 40)) < ABANDON_AFTER_MINUTES * 60000, "A worker on its longest day is not abandoned");
});
