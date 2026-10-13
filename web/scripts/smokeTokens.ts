// Smoke runs get their public API tokens the way an operator does: from the scheduler's own command line, which
// writes the scheduler's database. SCHEDULER_JAR names the built jar (default: the Maven build output), and the
// command connects with JDBC_DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD, the scheduler database's owner.
import { execFile } from "node:child_process";
import { resolve } from "node:path";
import { promisify } from "node:util";
import { TENANT_TOKEN_PATTERN } from "../lib/tenantTokens";

const run = promisify(execFile);
const jar = process.env.SCHEDULER_JAR ?? resolve(__dirname, "../../scheduler-service/target/scheduler-service-0.1.0-SNAPSHOT.jar");

async function command(...args: string[]): Promise<string> {
  try {
    return (await run("java", ["-jar", jar, ...args], { env: process.env, maxBuffer: 1024 * 1024 })).stdout;
  } catch (error) {
    const stderr = typeof error === "object" && error !== null && "stderr" in error ? String(error.stderr) : "";
    throw new Error(`Scheduler command ${args[0]} failed: ${stderr.trim() || String(error)}`);
  }
}

function field(output: string, name: string): string {
  const match = new RegExp(`^${name}: (\\S+)$`, "m").exec(output);
  if (match?.[1] === undefined) throw new Error(`The scheduler's issue-token output has no ${name}`);
  return match[1];
}

/** Issues a token for the tenant (created if it does not exist) and returns its ID and plaintext. */
export async function issueToken(tenantId: string, name: string, label: string): Promise<{ id: string; token: string }> {
  const output = await command("--command=issue-token", tenantId, name, label);
  const token = field(output, "token");
  if (!TENANT_TOKEN_PATTERN.test(token)) throw new Error("The scheduler issued a malformed token");
  return { id: field(output, "id"), token };
}

export async function revokeToken(tokenId: string): Promise<void> {
  await command("--command=revoke-token", tokenId);
}
