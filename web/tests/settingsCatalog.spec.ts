import { test, expect } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { openNewWorkOrder, scheduleMetro } from "./newWorkOrderPage";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("a reason code added, edited and retired in Settings is what the new work order form offers", async ({ page }) => {
  const metro = await scheduleMetro(prisma, "Settings catalog");
  try {
    await page.goto("/settings/reason-codes");
    await expect(page.getByRole("navigation", { name: "Pages" }).getByRole("link", { name: "Reason codes" })).toHaveAttribute("aria-current", "page");
    const add = page.getByRole("form", { name: "Add a reason code" });
    await add.getByLabel("Code").fill("UI_SET_LEAK");
    await add.getByLabel("Label").fill("Settings leak");
    await add.getByLabel("Minutes").fill("40");
    await add.getByLabel("Buffer (%)").fill("10");
    await add.getByRole("button", { name: "Add reason code" }).click();
    const row = page.locator('[data-reason-code="UI_SET_LEAK"]');
    await expect(row).toContainText("Settings leak");
    // 40 minutes plus 10% suggests 44.
    await expect(row.locator("td").nth(4)).toHaveText("44");
    await expect(row).toContainText("Active");

    // The same code cannot be added twice.
    await add.getByLabel("Code").fill("UI_SET_LEAK");
    await add.getByLabel("Label").fill("Again");
    await add.getByLabel("Minutes").fill("10");
    await add.getByRole("button", { name: "Add reason code" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "already exists" })).toBeVisible();

    await row.getByRole("button", { name: "Edit" }).click();
    await row.getByLabel("Minutes for UI_SET_LEAK").fill("721");
    await row.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "from 1 to 720" })).toBeVisible();
    await row.getByLabel("Minutes for UI_SET_LEAK").fill("60");
    await row.getByRole("button", { name: "Save" }).click();
    await expect(row.locator("td").nth(4)).toHaveText("66");
    expect(await prisma.serviceCatalog.findUnique({ where: { code: "UI_SET_LEAK" } })).toMatchObject({ estDurationMin: 60, bufferPct: 0.1, active: true });

    await expect((await openNewWorkOrder(page, metro.metroId)).getByLabel("Reason code", { exact: true }).locator('option[value="UI_SET_LEAK"]')).toHaveCount(1);
    await page.goto("/settings/reason-codes");
    await page.locator('[data-reason-code="UI_SET_LEAK"]').getByRole("button", { name: "Retire" }).click();
    await expect(page.locator('[data-reason-code="UI_SET_LEAK"]')).toContainText("Retired");
    await expect((await openNewWorkOrder(page, metro.metroId)).getByLabel("Reason code", { exact: true }).locator('option[value="UI_SET_LEAK"]')).toHaveCount(0);
  } finally {
    await prisma.serviceCatalog.deleteMany({ where: { code: "UI_SET_LEAK" } });
    await metro.cleanup();
  }
});

test("the accounts page counts the client's accounts and finds one by its street", async ({ page }) => {
  const importedAt = new Date("2026-11-01T12:00:00Z");
  const accounts = await Promise.all([
    prisma.account.create({ data: { clientId: DEFAULT_CLIENT_ID, accountNumber: "UI-SET-1", name: "Settings Person", phone: null, email: null,
      line1: "77 Settings Way", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.2601, lng: -95.9402, source: "WATERFLEX_IMPORT", importedAt } }),
    prisma.account.create({ data: { clientId: DEFAULT_CLIENT_ID, accountNumber: "UI-SET-2", name: null, phone: null, email: null,
      line1: "78 Settings Way", city: "Omaha", state: "NE", postalCode: "68102", lat: null, lng: null, source: "WATERFLEX_IMPORT", importedAt } }),
  ]);
  try {
    await page.goto("/settings/accounts");
    const total = await prisma.account.count({ where: { clientId: DEFAULT_CLIENT_ID } });
    await expect(page.locator("dt", { hasText: /^Accounts$/ }).locator("+ dd")).toHaveText(String(total));
    await page.getByLabel("Search accounts").fill("Settings Way");
    const found = page.getByRole("region", { name: "Matching accounts" });
    await expect(found.getByRole("row")).toHaveCount(3);
    await expect(found.getByRole("row", { name: /UI-SET-1/ })).toContainText("41.26010, -95.94020");
    await expect(found.getByRole("row", { name: /UI-SET-2/ })).toContainText("No WaterFlex coordinates (follow-up only)");
  } finally {
    await prisma.account.deleteMany({ where: { id: { in: accounts.map(account => account.id) } } });
  }
});
