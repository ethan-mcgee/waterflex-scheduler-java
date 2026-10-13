// The kit's response schemas must stay the spec's: same properties, same required fields, same enums. A contract
// change that the kit does not follow fails here, before a host sees a false PASS or FAIL.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parse } from "yaml";
import { z } from "zod";
import { ERROR_CODES, LocatedStop, SPEC_SCHEMAS } from "../src/api.ts";

interface SpecSchema { properties?: Record<string, { enum?: string[] }>; required?: string[] }

const spec: unknown = parse(readFileSync(new URL("../../../docs/api/openapi-v1.yaml", import.meta.url), "utf8"), { maxAliasCount: -1 });
const schemas = z.object({ components: z.object({ schemas: z.record(z.string(), z.unknown()) }) }).parse(spec).components.schemas;

function specSchema(name: string): SpecSchema {
  const schema = schemas[name];
  assert.ok(typeof schema === "object" && schema !== null, `The spec has no schema ${name}`);
  return schema as SpecSchema;
}

for (const [name, schema] of Object.entries(SPEC_SCHEMAS)) {
  test(`${name} mirrors the spec`, () => {
    const expected = specSchema(name);
    const shape = schema.shape as Record<string, z.ZodType>;
    assert.deepEqual(Object.keys(shape).sort(), Object.keys(expected.properties ?? {}).sort(), "properties");
    const required = Object.entries(shape).filter(([, field]) => !field.safeParse(undefined).success).map(([key]) => key).sort();
    assert.deepEqual(required, [...(expected.required ?? [])].sort(), "required");
  });
}

test("Problem error codes are the spec's", () => {
  assert.deepEqual([...ERROR_CODES].sort(), [...(specSchema("Problem").properties?.error?.enum ?? [])].sort());
});

test("LocatedStop sources are the spec's", () => {
  assert.deepEqual([...LocatedStop.shape.source.options].sort(), [...(specSchema("LocatedStop").properties?.source?.enum ?? [])].sort());
});

test("strict schemas refuse a field the spec does not define", () => {
  assert.equal(SPEC_SCHEMAS.WhoAmI.safeParse({ tenantId: "acme", extra: true }).success, false);
  assert.equal(SPEC_SCHEMAS.WhoAmI.safeParse({ tenantId: "acme" }).success, true);
});

test("instants must carry an offset", () => {
  assert.equal(SPEC_SCHEMAS.Hold.safeParse({ holdId: "h", offerId: "o", expiresAt: "2026-10-12T15:10:00" }).success, false);
  assert.equal(SPEC_SCHEMAS.Hold.safeParse({ holdId: "h", offerId: "o", expiresAt: "2026-10-12T15:10:00-05:00" }).success, true);
  assert.equal(SPEC_SCHEMAS.Hold.safeParse({ holdId: "h", offerId: "o", expiresAt: "2026-10-12T20:10:00.123456Z" }).success, true);
});
