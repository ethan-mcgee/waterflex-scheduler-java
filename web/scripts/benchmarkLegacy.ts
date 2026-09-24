import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { z } from "zod";
import { offer, required } from "../lib/contracts";

/** Benchmark-only adapter. The original server has neither deadlines nor completion metadata. */
export async function legacyBenchmarkServer(database: URL, engine: string, expectedHash: string) {
  assert.equal(database.pathname, "/waterflex_test");
  const schema = required(database.searchParams.get("schema"));
  assert.match(schema, /^benchmark_[a-z0-9_]+$/);
  const address = new URL(engine);
  assert.ok(["localhost", "127.0.0.1"].includes(address.hostname));
  assert.match(address.port, /^[1-9][0-9]*$/);
  let occupied = false;
  try { await fetch(`${engine}/health`, { signal: AbortSignal.timeout(500) }); occupied = true; } catch { /* Expected unused benchmark port. */ }
  assert.equal(occupied, false, "Legacy benchmark port is already occupied");
  const jar = required(process.env.BENCHMARK_LEGACY_JAR);
  assert.equal(createHash("sha256").update(await readFile(jar)).digest("hex"), expectedHash);
  const child: ChildProcess = spawn(required(process.env.BENCHMARK_JAVA), ["-Xmx768m", "-jar", jar, `--server.port=${address.port}`], {
    windowsHide: true, stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, JDBC_DATABASE_URL: `jdbc:postgresql://${database.hostname}:${database.port}/waterflex_test?currentSchema=${schema}`,
      DATABASE_USER: decodeURIComponent(database.username), DATABASE_PASSWORD: decodeURIComponent(database.password),
      ROUTING_URL: required(process.env.BENCHMARK_ROUTING_URL) },
  });
  let logs = "";
  child.stdout?.on("data", data => { logs = (logs + String(data)).slice(-16000); });
  child.stderr?.on("data", data => { logs = (logs + String(data)).slice(-16000); });
  let launchFailure: Error | null = null;
  child.on("error", error => { launchFailure = error; });
  const stop = async () => {
    if (child.pid == null || child.exitCode != null || child.signalCode != null) return;
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Legacy benchmark process did not stop")), 10000);
      child.once("exit", () => { clearTimeout(timeout); resolve(); });
      child.kill();
    });
  };
  try {
    const start = performance.now();
    while (true) {
      if (launchFailure) throw launchFailure;
      if (child.exitCode != null) throw new Error(`Legacy server exited: ${logs}`);
      try {
        const response = await fetch(`${engine}/health`, { signal: AbortSignal.timeout(500) });
        if (response.ok) break;
      } catch { /* Startup has not bound its socket yet. */ }
      if (performance.now() - start > 15000) throw new Error(`Legacy server startup timed out: ${logs}`);
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    return { stop };
  } catch (error) { await stop(); throw error; }
}

export async function legacyOffers(engine: string, jobId: string) {
  const response = await fetch(`${engine}/v1/offers`, {
    method: "POST", headers: { "Content-Type": "application/json", "x-internal-secret": process.env.INTERNAL_API_SECRET ?? "dev-only-change-me" },
    body: JSON.stringify({ jobId }), signal: AbortSignal.timeout(120000),
  });
  const body: unknown = await response.json();
  assert.equal(response.status, 200, JSON.stringify(body));
  const result = z.object({ jobId: z.string().min(1), offers: z.array(offer) }).parse(body);
  assert.equal(result.jobId, jobId);
  return result;
}
