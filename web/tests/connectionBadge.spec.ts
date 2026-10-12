import { test, expect } from "@playwright/test";

test("the sidebar shows when the active client cannot reach the scheduling API, and why", async ({ page }) => {
  // The browser test environment configures no scheduling API token, so the real route reports not connected.
  await page.goto("/schedule");
  const badge = page.locator(".sidebar-connection");
  await expect(badge).toHaveAttribute("data-state", "NOT_CONNECTED");
  await expect(badge).toContainText("Scheduling API not connected");
  await expect(badge).toContainText("README, Public API tokens");
});

test("the badge says connected only for a verified reply about the active client", async ({ page }) => {
  await page.route("**/api/clients/connection", route => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ clientId: "default", state: "CONNECTED" }) }));
  await page.goto("/schedule");
  await expect(page.locator(".sidebar-connection")).toHaveAttribute("data-state", "CONNECTED");

  await page.unroute("**/api/clients/connection");
  await page.route("**/api/clients/connection", route => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ clientId: "someone-else", state: "CONNECTED" }) }));
  await page.reload();
  await expect(page.locator(".sidebar-connection")).toHaveAttribute("data-state", "UNKNOWN");

  await page.unroute("**/api/clients/connection");
  await page.route("**/api/clients/connection", route => route.fulfill({ status: 200, contentType: "application/json", body: "null" }));
  await page.reload();
  await expect(page.locator(".sidebar-connection")).toHaveAttribute("data-state", "UNKNOWN");
});
