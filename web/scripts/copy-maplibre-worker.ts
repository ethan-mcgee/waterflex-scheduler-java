// maplibre-gl 6 runs its worker from a separate module that the bundler must not process (Next 14's minifier cannot
// parse it). Copy it, unchanged, into public/ under the installed version's name before `next dev` and `next build`;
// lib/maplibre.ts points maplibre at that path. Older copies are removed so only the installed version is served.
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { z } from "zod";

const root = join(__dirname, "..");
const pkg = z.object({ version: z.string().regex(/^\d+\.\d+\.\d+$/) }).parse(
  JSON.parse(readFileSync(join(root, "node_modules/maplibre-gl/package.json"), "utf8")));
const target = join(root, "public/maplibre");
const name = `maplibre-gl-worker-${pkg.version}.mjs`;
mkdirSync(target, { recursive: true });
for (const old of readdirSync(target)) if (old !== name) rmSync(join(target, old));
copyFileSync(join(root, "node_modules/maplibre-gl/dist/maplibre-gl-worker.mjs"), join(target, name));
console.log(`maplibre worker: public/maplibre/${name}`);
