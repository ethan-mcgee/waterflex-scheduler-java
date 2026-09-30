import { test, expect } from "@playwright/test";

const cursor = { id: "00000000-0000-4000-8000-000000000012", jobId: "durable-job" };
const status = { ...cursor, phase: "CANDIDATE_EVALUATION", elapsedMs: 12000, queueMs: 20, completedWork: 314, stopReason: null, offers: [] };

test("reload reconnects to an existing search and displays its published offer", async ({ page }) => {
  let ready = false;
  let starts = 0;
  await page.addInitScript(saved => localStorage.setItem("waterflex.bookingSearch", JSON.stringify(saved)), cursor);
  await page.route("**/api/book/search**", route => {
    if (route.request().method() === "POST") starts++;
    return route.fulfill({ json: ready ? { ...status, state: "AVAILABLE", phase: "FINISHED", stopReason: "COMPLETED", offers: [{
      offerId: "saved-offer", date: "2099-10-05", windowStart: "2099-10-05T14:00:00Z", windowEnd: "2099-10-05T18:00:00Z", expiresAt: "2099-10-05T13:00:00Z",
    }] } : { ...status, state: "RUNNING" } });
  });
  await page.goto("/book");
  await expect(page.getByText(/12 seconds elapsed. 314 options evaluated/)).toBeVisible();
  await page.reload();
  await expect(page.getByRole("button", { name: "Cancel search" })).toBeVisible();
  ready = true;
  await expect(page.getByRole("progressbar")).not.toBeVisible();
  await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
  expect(starts).toBe(0);
});

test("cancellation is explicit and survives the polling response race", async ({ page }) => {
  let cancelled = false;
  await page.addInitScript(saved => localStorage.setItem("waterflex.bookingSearch", JSON.stringify(saved)), cursor);
  await page.route("**/api/book/search**", route => {
    if (route.request().method() === "DELETE") cancelled = true;
    return route.fulfill({ json: { ...status, state: cancelled ? "CANCELLED" : "RUNNING" } });
  });
  await page.goto("/book");
  await page.getByRole("button", { name: "Cancel search" }).click();
  await expect(page.getByText("The appointment search was cancelled.")).toBeVisible();
  expect(cancelled).toBe(true);
  expect(await page.evaluate(() => localStorage.getItem("waterflex.bookingSearch"))).toBeNull();
});
