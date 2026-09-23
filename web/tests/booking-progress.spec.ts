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
  } finally {
    finish?.();
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
  }
});
