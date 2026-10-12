import { existsSync, mkdirSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { TENANT_ID_PATTERN } from "./tenantTokens";

/** The label of tokens this bootstrap issues; a reissue revokes the earlier ones. */
export const LOCAL_TOKEN_LABEL = "local-compose";

export type BootstrapDependencies = {
  clients(): Promise<{ id: string; name: string }[]>;
  /** Issues a token for the tenant with the client's ID, revoking earlier LOCAL_TOKEN_LABEL tokens. */
  issue(client: { id: string; name: string }): Promise<string>;
};

/**
 * Local Compose only: gives every client without a token file one, written as `<dir>/<clientId>` (mode 0600, temp file
 * then rename). Existing files are kept, so a restart never rotates working tokens. Refuses unless explicitly enabled.
 */
export async function bootstrapLocalTokens(env: Readonly<Record<string, string | undefined>>, dependencies: BootstrapDependencies):
  Promise<{ issued: string[]; kept: string[] }> {
  if (env.LOCAL_TOKEN_BOOTSTRAP !== "true") throw new Error("Local token bootstrap is disabled (set LOCAL_TOKEN_BOOTSTRAP=true, local Compose only)");
  const dir = env.SCHEDULER_API_TOKEN_DIR;
  if (dir === undefined || dir === "") throw new Error("SCHEDULER_API_TOKEN_DIR is not set");
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const issued: string[] = [];
  const kept: string[] = [];
  for (const client of await dependencies.clients()) {
    if (!TENANT_ID_PATTERN.test(client.id)) throw new Error(`Client ${client.id} cannot have a token file`);
    const file = join(dir, client.id);
    if (existsSync(file)) { kept.push(client.id); continue; }
    const token = await dependencies.issue(client);
    const temporary = `${file}.tmp`;
    writeFileSync(temporary, `${token}\n`, { mode: 0o600 });
    renameSync(temporary, file);
    issued.push(client.id);
  }
  return { issued, kept };
}
