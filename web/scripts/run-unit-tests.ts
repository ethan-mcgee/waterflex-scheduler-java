import { readdirSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

function discover(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? discover(path) : entry.name.endsWith(".test.ts") ? [path] : [];
  });
}

const files = [ ...discover("lib"), ...discover("scripts") ].sort();
if (!files.length) throw new Error("No portal unit tests discovered");
console.log(`Discovered ${files.length} unit test files:\n${files.join("\n")}`);
mkdirSync("test-results", { recursive: true });
const result = spawnSync(process.execPath, ["--import", "tsx", "--test",
  "--test-reporter=spec", "--test-reporter=junit",
  "--test-reporter-destination=stdout", "--test-reporter-destination=test-results/unit.xml", ...files], { stdio: "inherit" });
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
