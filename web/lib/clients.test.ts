import { test } from "node:test";
import assert from "node:assert/strict";
import { chooseClient, CLIENT_ID, DEFAULT_CLIENT_ID } from "./clients";

const acme = { id: "acme", name: "Acme Water" };
const brook = { id: "brook", name: "Brook Softeners" };
const fallback = { id: DEFAULT_CLIENT_ID, name: "Default client" };

test("the remembered client wins while it exists", () => {
  assert.deepEqual(chooseClient("brook", [acme, brook, fallback]), brook);
});

test("a missing or deleted remembered client falls back to the default client", () => {
  assert.deepEqual(chooseClient(undefined, [acme, fallback]), fallback);
  assert.deepEqual(chooseClient("gone", [acme, fallback]), fallback);
});

test("without a default client, the first client by name is chosen", () => {
  assert.deepEqual(chooseClient("gone", [brook, acme]), acme);
});

test("no clients means no choice rather than an invented one", () => {
  assert.equal(chooseClient("acme", []), null);
  assert.equal(chooseClient(undefined, []), null);
});

test("client IDs are lowercase slugs", () => {
  for (const id of ["default", "acme", "a", "acme-water-2"]) assert.ok(CLIENT_ID.test(id), id);
  for (const id of ["", "Acme", "-acme", "acme-", "acme_water", "a".repeat(65)]) assert.ok(!CLIENT_ID.test(id), id);
});
