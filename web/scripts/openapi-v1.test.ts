// Contract checks for docs/api/openapi-v1.yaml: structure, examples against schemas, and
// agreement with the /api/v1 routes the Java scheduler actually serves.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { load } from "js-yaml";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type JsonObject = { [key: string]: Json };

const SPEC_PATH = join("..", "docs", "api", "openapi-v1.yaml");
const JAVA_ROOT = join("..", "scheduler-service", "src", "main", "java");
const METHODS = ["get", "post", "put", "patch", "delete"] as const;

function isJson(value: unknown): value is Json {
  if (value === null || typeof value === "boolean" || typeof value === "string") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.every(isJson);
  return typeof value === "object" && Object.values(value).every(isJson);
}

function array(value: Json | undefined, where: string): Json[] {
  assert.ok(Array.isArray(value), `${where} must be an array`);
  return value;
}

function object(value: Json | undefined, where: string): JsonObject {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${where} must be an object`);
  return value;
}

const parsed: unknown = load(readFileSync(SPEC_PATH, "utf8"));
assert.ok(isJson(parsed), "spec must be plain JSON-compatible YAML");
const spec = object(parsed, "spec");
const components = object(spec.components, "components");
const paths = object(spec.paths, "paths");

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema({ $id: "spec", components }, "spec");

function validator(schema: Json | undefined, where: string) {
  const ref = object(schema, where).$ref;
  assert.ok(typeof ref === "string" && ref.startsWith("#/components/schemas/"), `${where} must reference a component schema`);
  return ajv.compile({ $ref: `spec${ref}` });
}

function schemaFor(name: string) {
  return ajv.compile({ $ref: `spec#/components/schemas/${name}` });
}

function exampleValue(example: Json | undefined, where: string): Json {
  const entry = object(example, where);
  if (typeof entry.$ref === "string") {
    const name = entry.$ref.replace("#/components/examples/", "");
    return exampleValue(object(components.examples, "components.examples")[name], entry.$ref);
  }
  assert.ok("value" in entry, `${where} needs a value`);
  return entry.value ?? null;
}

type Operation = { path: string; method: string; operation: JsonObject };
const operations: Operation[] = Object.entries(paths).flatMap(([path, item]) =>
  METHODS.flatMap(method => {
    const operation = object(item, path)[method];
    return operation === undefined ? [] : [{ path, method, operation: object(operation, `${method} ${path}`) }];
  }));

function resolveResponse(response: Json | undefined, where: string): JsonObject {
  const entry = object(response, where);
  if (typeof entry.$ref === "string") return object(object(components.responses, "responses")[entry.$ref.replace("#/components/responses/", "")], entry.$ref);
  return entry;
}

test("spec is OpenAPI 3.1, tenant-authenticated and versioned under /api/v1", () => {
  assert.equal(spec.openapi, "3.1.0");
  assert.deepEqual(spec.security, [{ tenantToken: [] }]);
  assert.ok(operations.length >= 9);
  const ids = new Set<string>();
  for (const { path, method, operation } of operations) {
    const where = `${method.toUpperCase()} ${path}`;
    assert.match(path, /^\/api\/v1\//, where);
    assert.equal(operation.security, undefined, `${where} must not override tenant security`);
    assert.ok(typeof operation.operationId === "string" && !ids.has(operation.operationId), `${where} needs a unique operationId`);
    ids.add(operation.operationId);
    assert.ok(operation["x-implementation"] === "implemented" || operation["x-implementation"] === "planned", `${where} x-implementation`);
    assert.ok(object(operation.responses, where)["401"] !== undefined, `${where} must document 401`);
  }
});

test("every request and response example validates against its schema", () => {
  let checked = 0;
  for (const { path, method, operation } of operations) {
    const where = `${method.toUpperCase()} ${path}`;
    const bodies: [string, JsonObject][] = [];
    if (operation.requestBody !== undefined) bodies.push([`${where} request`, object(operation.requestBody, where)]);
    for (const [status, response] of Object.entries(object(operation.responses, where)))
      bodies.push([`${where} ${status}`, resolveResponse(response, `${where} ${status}`)]);
    for (const [label, body] of bodies) {
      if (body.content === undefined) continue;
      const media = object(object(body.content, label)["application/json"], `${label} application/json`);
      const validate = validator(media.schema, label);
      const examples = object(media.examples, `${label} examples`);
      assert.ok(Object.keys(examples).length > 0, `${label} needs an example`);
      for (const [name, example] of Object.entries(examples)) {
        const value = exampleValue(example, `${label} ${name}`);
        assert.ok(validate(value), `${label} example ${name}: ${ajv.errorsText(validate.errors)}`);
        checked++;
      }
    }
  }
  assert.ok(checked >= 20, `only ${checked} examples checked`);
});

test("schemas reject unknown fields, a requested tenant, numeric money and overtime", () => {
  const request = object(structuredClone(exampleValue(object(components.examples, "examples").DailyProposalRequest, "DailyProposalRequest")), "request");
  const daily = schemaFor("DailyProposalRequest");
  assert.ok(daily(request));
  assert.equal(daily({ ...request, tenantId: "acme-water" }), false);
  const snapshot = object(request.snapshot, "snapshot");
  assert.equal(daily({ ...request, snapshot: { ...snapshot, rates: { ...object(snapshot.rates, "rates"), regularHourly: 20 } } }), false);
  assert.equal(daily({ ...request, snapshot: { ...snapshot, rates: { ...object(snapshot.rates, "rates"), travelBufferPct: "0.20" } } }), false);
  assert.equal(daily({ ...request, requestId: "not-a-uuid" }), false);
  const proposal = object(structuredClone(exampleValue(object(components.examples, "examples").DailyProposal, "DailyProposal")), "proposal");
  assert.equal(schemaFor("DailyProposal")({ ...proposal, overtimeMinutes: 1 }), false);
  const location = schemaFor("Location");
  assert.ok(location({ lat: 41.2, lng: -96 }));
  assert.equal(location({ lat: 41.2 }), false);
  assert.equal(location({}), false);
  const offers = object(structuredClone(exampleValue(object(components.examples, "examples").OfferSet, "OfferSet")), "offers");
  const offerList = array(offers.offers, "offers.offers");
  assert.equal(schemaFor("OfferSet")({ ...offers, offers: [...offerList, ...offerList, ...offerList] }), false);
  assert.equal(schemaFor("CommitRequest")({ requestId: "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", technicianDays: [] }), false);
});

function javaRoutes(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return javaRoutes(path);
    if (!entry.name.endsWith(".java")) return [];
    return [...readFileSync(path, "utf8").matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\("(\/api\/v1\/[^"]*)"\)/g)]
      .map(match => `${(match[1] ?? "").toLowerCase()} ${(match[2] ?? "").replace(/\{[^}]+\}/g, "{}")}`);
  });
}

test("operations marked implemented are exactly the /api/v1 routes the scheduler serves", () => {
  const served = javaRoutes(JAVA_ROOT).sort();
  const implemented = operations.filter(({ operation }) => operation["x-implementation"] === "implemented")
    .map(({ path, method }) => `${method} ${path.replace(/\{[^}]+\}/g, "{}")}`).sort();
  assert.ok(served.length > 0, "no /api/v1 routes found in scheduler-service");
  assert.deepEqual(served, implemented);
});
