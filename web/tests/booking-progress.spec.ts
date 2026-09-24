import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("appointment search shows indeterminate progress and retains inputs after failure", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_SEARCH_PROGRESS", name: "Progress test service", estDurationMin: 60 } });
  let finish: (() => void) | undefined;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  try {
    await page.route("**/api/book", async route => {
      await pending;
      await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: "Search incomplete. Please retry." }) });
    });
    await page.goto("/book");
    await page.getByRole("radio", { name: /Progress test service/ }).check();
    for (const [label, value] of Object.entries({ "First name": "Keep", "Last name": "Details", Email: "customer@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("1 Main St");
    await page.getByRole("button", { name: /see available times/i }).click();
    const progress = page.getByRole("progressbar", { name: "Finding available appointments" });
    await expect(progress).toBeVisible();
    await expect(progress).not.toHaveAttribute("value");
    await expect(progress).not.toHaveAttribute("aria-valuenow");
    finish?.();
    await expect(page.getByRole("main").getByRole("alert")).toHaveText("Search incomplete. Please retry.");
    await expect(progress).not.toBeVisible();
    await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
    await expect(page.getByRole("button", { name: /see available times/i })).toBeEnabled();
    await page.unroute("**/api/book");
    for (const [outcome, message] of [
      ["SEARCH_INCOMPLETE", "The appointment search did not finish. Please retry to check available times."],
      ["ROUTING_UNAVAILABLE", "Road routing is temporarily unavailable. Please retry your appointment search."],
      ["SERVICE_BUSY", "Appointment search is temporarily busy. Please retry."],
      ["SCHEDULE_CONFLICT", "The schedule changed during your search. Please retry to get current times."],
    ] as const) {
      await page.route("**/api/book", route => route.fulfill({ status: 200, contentType: "application/json",
        body: JSON.stringify({ jobId: "search-job", offers: [], search: { outcome, prescribedSearchCompleted: false, elapsedMs: 5, retryable: true } }) }));
      await page.getByRole("button", { name: /see available times/i }).click();
      await expect(page.getByRole("main").getByRole("alert")).toHaveText(message);
      await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
      await expect(page.getByRole("button", { name: /see available times/i })).toBeEnabled();
      await page.unroute("**/api/book");
    }
    const measurements = await page.evaluate(() => performance.getEntriesByName("waterflex.booking-api", "measure")
      .map(entry => {
        if (!(entry instanceof PerformanceMeasure)) throw new Error("Expected a browser duration measurement");
        const detail: unknown = entry.detail;
        return { duration: entry.duration, detail };
      }));
    expect(measurements).toHaveLength(5);
    for (const measurement of measurements) {
      expect(measurement.duration).toBeGreaterThanOrEqual(0);
      expect(measurement.detail).toEqual({ flow: "initial", includesAddressValidation: true });
    }
    await page.evaluate(() => {
      const original = window.fetch.bind(window);
      window.fetch = (input, init) => input === "/api/book" ? new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener("abort", () => {
          document.documentElement.dataset.bookingAborted = "true";
          reject(new DOMException("Abandoned fixture search", "AbortError"));
        }, { once: true });
      }) : original(input, init);
    });
    await page.getByRole("button", { name: /see available times/i }).click();
    await expect(progress).toBeVisible();
    await page.getByRole("link", { name: "Technicians", exact: true }).click();
    await expect(page.locator("html")).toHaveAttribute("data-booking-aborted", "true");
  } finally {
    finish?.();
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
  }
});
