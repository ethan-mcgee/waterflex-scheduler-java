import assert from "node:assert/strict";
import { test } from "node:test";
import { readToken } from "../src/token.ts";

const unread = () => { throw new Error("no file should be read"); };

test("a token comes from the named environment variable without touching a file", () => {
  assert.equal(readToken(undefined, "SCHEDULER_STUDY_TOKEN", { SCHEDULER_STUDY_TOKEN: " wfs_abc\n" }, unread), "wfs_abc");
});

test("a token comes from a file, or from standard input for -", () => {
  const sources: Array<string | number> = [];
  const read = (source: string | number) => { sources.push(source); return "wfs_file\r\n"; };
  assert.equal(readToken("token.txt", undefined, {}, read), "wfs_file");
  assert.equal(readToken("-", undefined, {}, read), "wfs_file");
  assert.deepEqual(sources, ["token.txt", 0]);
});

test("exactly one source is required", () => {
  assert.throws(() => readToken(undefined, undefined, {}, unread), /exactly one of --token-file and --token-env/);
  assert.throws(() => readToken("token.txt", "SCHEDULER_STUDY_TOKEN", { SCHEDULER_STUDY_TOKEN: "wfs_abc" }, unread), /exactly one/);
});

test("an unset, empty or badly named variable is refused, never sent as an empty bearer", () => {
  assert.throws(() => readToken(undefined, "SCHEDULER_STUDY_TOKEN", {}, unread), /SCHEDULER_STUDY_TOKEN named by --token-env is unset or empty/);
  assert.throws(() => readToken(undefined, "SCHEDULER_STUDY_TOKEN", { SCHEDULER_STUDY_TOKEN: "  " }, unread), /unset or empty/);
  assert.throws(() => readToken(undefined, "wfs_abc def", {}, unread), /environment variable's name/);
});

test("an unreadable or empty file is refused", () => {
  assert.throws(() => readToken("missing", undefined, {}, () => { throw new Error("ENOENT"); }), /Cannot read --token-file: ENOENT/);
  assert.throws(() => readToken("empty", undefined, {}, () => "\n"), /holds no token/);
});
