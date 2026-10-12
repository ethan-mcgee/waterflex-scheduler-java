import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { bootstrapLocalTokens } from "./localTokenBootstrap";

const TOKEN = `wfs_${"c".repeat(43)}`;

test("the bootstrap issues a token file only for clients without one and refuses unless enabled", async () => {
  const dir = mkdtempSync(join(tmpdir(), "wf-tokens-"));
  try {
    writeFileSync(join(dir, "kept"), "existing\n");
    const issuedFor: string[] = [];
    const dependencies = {
      clients: async () => [{ id: "default", name: "Default client" }, { id: "kept", name: "Kept" }],
      issue: async (client: { id: string }) => { issuedFor.push(client.id); return TOKEN; },
    };
    await assert.rejects(bootstrapLocalTokens({ SCHEDULER_API_TOKEN_DIR: dir }, dependencies), /disabled/);
    await assert.rejects(bootstrapLocalTokens({ LOCAL_TOKEN_BOOTSTRAP: "1", SCHEDULER_API_TOKEN_DIR: dir }, dependencies), /disabled/);
    await assert.rejects(bootstrapLocalTokens({ LOCAL_TOKEN_BOOTSTRAP: "true" }, dependencies), /SCHEDULER_API_TOKEN_DIR/);
    assert.deepEqual(issuedFor, [], "Nothing is issued while refused");

    const env = { LOCAL_TOKEN_BOOTSTRAP: "true", SCHEDULER_API_TOKEN_DIR: dir };
    assert.deepEqual(await bootstrapLocalTokens(env, dependencies), { issued: ["default"], kept: ["kept"] });
    assert.equal(readFileSync(join(dir, "default"), "utf8"), `${TOKEN}\n`);
    assert.equal(readFileSync(join(dir, "kept"), "utf8"), "existing\n", "An existing file is never rotated");
    assert.ok(!existsSync(join(dir, "default.tmp")));
    if (process.platform !== "win32") assert.equal(statSync(join(dir, "default")).mode & 0o777, 0o600);

    assert.deepEqual(await bootstrapLocalTokens(env, dependencies), { issued: [], kept: ["default", "kept"] }, "A rerun issues nothing");
    assert.deepEqual(issuedFor, ["default"]);

    await assert.rejects(bootstrapLocalTokens(env, { ...dependencies, clients: async () => [{ id: "../x", name: "Bad" }] }), /cannot have a token file/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
