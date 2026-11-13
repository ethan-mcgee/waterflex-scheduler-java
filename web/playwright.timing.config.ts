import { defineConfig } from "@playwright/test";

// The move check timing study (infra/run-move-check-timing.ps1), not a regression test: it drags against a production
// build of the portal the start script runs, connected to the rehearsal scheduler, so it starts no server of its own
// and needs none of the browser tests' database guards. One worker and no retries: every drag is a measurement.
const portal = process.env.TIMING_PORTAL_URL;
if (portal === undefined || portal === "") throw new Error("Set TIMING_PORTAL_URL to the portal the start script runs");

export default defineConfig({
  testDir: "./timing",
  testMatch: /.*\.timing\.ts$/,
  workers: 1,
  retries: 0,
  timeout: 4 * 60 * 60_000,
  reporter: [["list"]],
  use: { baseURL: portal, browserName: "chromium", headless: true, trace: "off", screenshot: "only-on-failure" },
});
