import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { z } from "zod";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("dealership creation selects the new owner for depot setup", async ({ page }) => {
  const name = `New browser dealership ${Date.now()}`;
  let createdId: string | null = null;
  try {
    await page.goto("/dealerships");
    await page.getByPlaceholder("Foothills Nissan").fill(name);
    await page.getByRole("button", { name: "Create dealership" }).click();
    await expect(page.getByRole("status").last()).toContainText("Dealership created");
    const dealer = await prisma.dealership.findFirstOrThrow({ where: { name } });
    createdId = dealer.id;
    await expect(page.getByLabel("Dealership", { exact: true })).toHaveValue(dealer.id);
  } finally {
    if (createdId) await prisma.dealership.delete({ where: { id: createdId } });
  }
});

test("address changes discard stale geocodes and unavailable tiles retain a confirmable pin", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Depot setup browser metro", timezone: "America/Chicago" } });
  const dealer = await prisma.dealership.create({ data: { name: "Depot setup browser dealer" } });
  try {
    let first: (() => void) | undefined;
    await page.route("**/api/technicians/geocode", async route => {
      const address = z.object({ line1: z.string() }).parse(route.request().postDataJSON() as unknown);
      if (address.line1 === "1 Old Street") {
        await new Promise<void>(resolve => { first = resolve; });
        try { await route.fulfill({ json: { candidates: [{ lat: 40.1, lng: -96.1, precision: "ROOFTOP" }] } }); } catch { /* aborted request */ }
      } else {
        await route.fulfill({ json: { candidates: [{ lat: 41.25, lng: -95.93, precision: "ROOFTOP" }] } });
      }
    });
    await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ status: 503 }));
    await page.route("**/api/depots", route => route.fulfill({ status: 422, json: { error: "Pin must be within 250 meters" } }));
    await page.goto("/dealerships");
    await page.getByLabel("Depot name").fill("Test depot");
    await page.getByLabel("Street address").fill("1 Old Street");
    await page.getByLabel("City").fill("Omaha");
    await page.getByLabel("State").fill("NE");
    await page.getByLabel("Postal code").fill("68102");
    await expect.poll(() => first != null).toBe(true);
    await page.getByLabel("Street address").fill("2 New Street");
    first?.();
    await expect(page.getByText("Pin: 41.250000, -95.930000")).toBeVisible();
    await expect(page.getByText("Map tiles are unavailable at this address.", { exact: false })).toBeVisible();
    await page.getByRole("button", { name: "Create depot" }).click();
    await expect(page.getByText("Pin must be within 250 meters")).toBeVisible();
    await expect(page.getByLabel("Street address")).toHaveValue("2 New Street");
  } finally {
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("depot counts and policy history stay visible under the dealership", async ({ page }) => {
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  const next = new Date(`${today}T00:00:00Z`); next.setUTCDate(next.getUTCDate() + 4);
  const dealer = await prisma.dealership.create({ data: { name: "Policy browser dealer" } });
  const metro = await prisma.metro.create({ data: { name: "Policy browser metro", timezone: "America/Chicago" } });
  const first = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: metro.id, name: "Current browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: [{ effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" },
      { effectiveDate: next, departure: "DEPOT", returnTo: "HOME" }] } } });
  const second = await prisma.depot.create({ data: { dealershipId: dealer.id, metroId: metro.id, name: "Future browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { name: "Policy browser tech", color: "#2563eb", homeLat: 41.25, homeLng: -95.93,
    shiftStartMin: 480, shiftEndMin: 1020, depotAssignments: { create: [
      { effectiveDate: new Date("1900-01-01T00:00:00Z"), depotId: first.id }, { effectiveDate: next, depotId: second.id },
    ] } } });
  try {
    let saveAttempts = 0;
    await page.route(`**/api/depots/${first.id}/policy`, route => {
      saveAttempts++;
      return saveAttempts === 1 ? route.fulfill({ status: 409, json: { error: "Active hold on a booked day" } }) :
        route.fulfill({ json: { success: true, effectiveDate: next.toISOString().slice(0, 10) } });
    });
    await page.goto("/dealerships");
    await expect(page.getByText("1 active technicians today")).toHaveCount(0);
    await expect(page.getByText("1 active", { exact: true })).toBeVisible();
    const dealerPosition = await page.getByText("Policy browser dealer", { exact: true }).last().boundingBox();
    const depotPosition = await page.getByText("Current browser depot", { exact: true }).boundingBox();
    expect(dealerPosition).not.toBeNull();
    expect(depotPosition).not.toBeNull();
    const depotIndent = await page.getByText("Current browser depot", { exact: true }).evaluate(element => Number.parseFloat(getComputedStyle(element).paddingLeft));
    if (dealerPosition && depotPosition) expect(depotPosition.x + depotIndent).toBeGreaterThan(dealerPosition.x);
    await page.getByRole("button", { name: "Edit policy" }).first().click();
    await expect(page.getByText("Current policy from 1900-01-01", { exact: false })).toBeVisible();
    await expect(page.getByText(`Scheduled for ${next.toISOString().slice(0, 10)}`, { exact: false })).toBeVisible();
    await page.getByRole("group", { name: "Return" }).last().getByRole("button", { name: "Depot" }).click();
    await page.getByRole("button", { name: "Save policy change" }).click();
    await expect(page.getByText("Active hold on a booked day")).toBeVisible();
    await expect(page.getByRole("group", { name: "Return" }).last().getByRole("button", { name: "Depot" })).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: "Save policy change" }).click();
    await expect(page.getByRole("status").last()).toContainText(next.toISOString().slice(0, 10));
  } finally {
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depot.deleteMany({ where: { id: { in: [first.id, second.id] } } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
