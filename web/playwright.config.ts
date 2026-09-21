import { defineConfig } from "@playwright/test";

if (!process.env.DATABASE_URL?.includes("schema=nullability_ui")) throw new Error("Browser tests require the isolated nullability_ui schema");
export default defineConfig({
  testDir: "./tests", workers: 1, timeout: 60000,
  use: { baseURL: "http://127.0.0.1:13000", headless: true },
  webServer: { command: "npm run dev -- --port 13000", url: "http://127.0.0.1:13000", reuseExistingServer: false, timeout: 120000 },
});
