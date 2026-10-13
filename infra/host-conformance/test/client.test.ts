import assert from "node:assert/strict";
import { createServer, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { CaseFailure, type Case, type Context } from "../src/cases.ts";
import { SchedulerApi } from "../src/client.ts";
import { runCase } from "../src/report.ts";

const TOKEN = `wfs_${"a".repeat(43)}`;
let seen: IncomingMessage | undefined;
const server = createServer((request, response) => {
  seen = request;
  if (request.url === "/empty") response.writeHead(202).end();
  else if (request.url === "/text") response.writeHead(500, { "Content-Type": "text/plain" }).end("Internal Server Error");
  else response.writeHead(200, { "Content-Type": "application/json" }).end('{"tenantId":"acme"}');
});
let base = "";

before(async () => {
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => { server.close(); });

test("an empty body reads as null and a non-JSON body as undefined, never as an empty object", async () => {
  const api = new SchedulerApi(base, TOKEN);
  assert.equal((await api.call("DELETE", "/empty")).json, null);
  assert.equal((await api.call("GET", "/text")).json, undefined);
  assert.deepEqual((await api.call("GET", "/json")).json, { tenantId: "acme" });
});

test("the token is sent unless a case sends another or none", async () => {
  const api = new SchedulerApi(`${base}/`, TOKEN);
  await api.call("GET", "/json");
  assert.equal(seen?.headers.authorization, `Bearer ${TOKEN}`);
  await api.call("GET", "/json", undefined, { token: null });
  assert.equal(seen?.headers.authorization, undefined);
  await api.call("GET", "/json", undefined, { token: "wfs_other" });
  assert.equal(seen?.headers.authorization, "Bearer wfs_other");
});

test("a malformed token, URL or scheme is refused before any call", () => {
  assert.throws(() => new SchedulerApi(base, "not-a-token"), /does not hold a scheduler token/);
  assert.throws(() => new SchedulerApi("scheduler.internal", TOKEN), /not a URL/);
  assert.throws(() => new SchedulerApi("ftp://scheduler.internal", TOKEN), /http or https/);
  assert.throws(() => new SchedulerApi(`${base}?tenant=x`, TOKEN), /no query/);
});

test("a broken expectation and an unexpected error both fail the case with a reason", async () => {
  const context = {} as Context;
  const broken: Case = { group: "Integration", name: "broken", proves: "", run: () => Promise.reject(new CaseFailure("expected 201, got 400")) };
  const crashed: Case = { group: "Integration", name: "crashed", proves: "", run: () => Promise.reject(new TypeError("fetch failed")) };
  const passed: Case = { group: "Integration", name: "passed", proves: "", run: () => Promise.resolve() };
  assert.deepEqual([(await runCase(broken, context)).failure, (await runCase(crashed, context)).failure, (await runCase(passed, context)).failure],
    ["expected 201, got 400", "Unexpected error: TypeError: fetch failed", null]);
  assert.equal((await runCase(passed, context)).status, "PASS");
});
