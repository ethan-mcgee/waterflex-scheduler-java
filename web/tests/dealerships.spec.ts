import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("dealership policy controls save independent departure and return endpoints", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Dealership UI metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Dealership UI fixture" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Dealership UI depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  try {
    const effectiveDate = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
    let submitted: unknown = null;
    await page.route(`**/api/depots/${depot.id}/policy`, async route => {
      submitted = route.request().postDataJSON() as unknown;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true, effectiveDate }) });
    });
    await page.goto("/dealerships");
    await page.getByRole("button", { name: "Edit policy" }).click();
    await page.getByRole("group", { name: "Departure" }).last().getByRole("button", { name: "Depot" }).click();
    await page.getByRole("button", { name: "Save policy change" }).click();
    await expect(page.getByText(`Saved Dealership UI depot policy for ${effectiveDate}. Booked routes were checked.`)).toBeVisible();
    expect(submitted).toEqual({ departure: "DEPOT", returnTo: "HOME" });
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("detail editors retain failed input and legacy depot address is explicit", async ({ page, request }) => {
  const metro = await prisma.metro.create({ data: { name: "Detail UI metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Detail UI dealer" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Detail UI depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  try {
    expect((await request.patch(`/api/dealerships/${dealership.id}`, { data: { name: "" } })).status()).toBe(400);
    expect((await request.patch(`/api/depots/${depot.id}`, { data: { name: "Changed", address: { line1: "1 Main", city: "Omaha", state: "NE", postalCode: "68102" } } })).status()).toBe(400);
    await page.route(`**/api/dealerships/${dealership.id}`, route => route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ error: "Name conflict" }) }));
    await page.route(`**/api/depots/${depot.id}`, route => route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ error: "Active hold prevents edit" }) }));
    await page.goto("/dealerships");
    await page.getByRole("button", { name: "Edit dealership" }).last().click();
    await page.getByLabel("Dealership name").fill("Retained dealership");
    await page.getByRole("button", { name: "Save dealership" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Name conflict" })).toBeVisible();
    await expect(page.getByLabel("Dealership name")).toHaveValue("Retained dealership");
    await page.unroute(`**/api/dealerships/${dealership.id}`);
    await page.getByRole("button", { name: "Save dealership" }).click();
    await expect(page.getByText("Dealership name saved.")).toBeVisible();
    expect((await prisma.dealership.findUniqueOrThrow({ where: { id: dealership.id } })).name).toBe("Retained dealership");
    await page.getByRole("button", { name: "Edit details" }).last().click();
    await expect(page.getByText("Saved address unavailable or incomplete. Enter the full address to add one.")).toBeVisible();
    await page.getByLabel("Depot name").last().fill("Retained depot");
    await page.getByRole("button", { name: "Save depot details" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Active hold prevents edit" })).toBeVisible();
    await expect(page.getByLabel("Depot name").last()).toHaveValue("Retained depot");
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("changing a saved address requires a newly confirmed pin", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Address edit metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Address edit dealer" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Address edit depot", lat: 41.25, lng: -95.93,
    addressLine1: "1 Main St", addressCity: "Omaha", addressState: "NE", addressPostalCode: "68102",
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  try {
    let submitted: unknown = null;
    await page.route("**/api/technicians/geocode", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ candidates: [{ lat: 41.25, lng: -95.93, precision: "ROOFTOP" }] }) }));
    await page.route(`**/api/depots/${depot.id}`, route => { submitted = route.request().postDataJSON() as unknown; return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true }) }); });
    await page.goto("/dealerships");
    await page.getByRole("button", { name: "Edit details" }).last().click();
    await expect(page.getByLabel("Street address").last()).toHaveValue("1 Main St");
    await page.getByLabel("Street address").last().fill("2 Main St");
    await expect(page.getByRole("button", { name: "Save depot details" })).toBeDisabled();
    await page.getByRole("button", { name: "Confirm pin" }).click();
    await page.getByRole("button", { name: "Save depot details" }).click();
    await expect(page.getByText("Depot details saved. Booked routes were checked.")).toBeVisible();
    expect(submitted).toEqual({ name: "Address edit depot", address: { line1: "2 Main St", city: "Omaha", state: "NE", postalCode: "68102" }, confirmedPin: { lat: 41.25, lng: -95.93 } });
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
