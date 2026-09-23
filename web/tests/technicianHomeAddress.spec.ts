import { expect, test } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("street result needs a working map and address edits discard its confirmation", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Home pin browser metro", timezone: "America/Chicago" } });
  const dealer = await prisma.dealership.create({ data: { name: "Home pin browser dealer" } });
  const depot = await prisma.depot.create({ data: { name: "Home pin browser depot", metroId: metro.id, dealershipId: dealer.id, lat: 41.16, lng: -96.01 } });
  try {
    await page.route("**/api/technicians/geocode", route => route.fulfill({ json: { candidates: [{
      lat: 41.1637462, lng: -96.0079032, precision: "APPROXIMATE",
      bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 },
    }] } }));
    await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ status: 503 }));
    await page.goto("/technicians");
    await page.getByRole("button", { name: "+ Add technician" }).click();
    await page.getByLabel("Street address").fill("2125 Crest Ridge Dr");
    await page.getByLabel("city", { exact: true }).fill("Papillion");
    await page.getByLabel("state", { exact: true }).fill("NE");
    await page.getByLabel("Postal code").fill("68133");
    await expect(page.getByText("house number 2125 was not verified", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirm home pin" })).toBeDisabled();
    await expect(page.getByText("Home pin placement is blocked", { exact: false })).toBeVisible();
    await page.getByLabel("Street address").fill("2126 Crest Ridge Dr");
    await expect(page.getByText("house number 2125 was not verified", { exact: false })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Create technician" })).toBeDisabled();
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("a confirmed street pin is cleared by an address edit", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Home confirmation browser metro", timezone: "America/Chicago" } });
  const dealer = await prisma.dealership.create({ data: { name: "Home confirmation browser dealer" } });
  const depot = await prisma.depot.create({ data: { name: "Home confirmation browser depot", metroId: metro.id, dealershipId: dealer.id, lat: 41.16, lng: -96.01 } });
  try {
    await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ json: { bounds: [-97.5, 40.5, -95.2, 42.1] } }));
    await page.route("http://localhost:8083/omaha/**/*.mvt", route => route.fulfill({ status: 200, contentType: "application/x-protobuf", body: "" }));
    await page.route("**/api/technicians/geocode", route => route.fulfill({ json: { candidates: [{
      lat: 41.1637462, lng: -96.0079032, precision: "APPROXIMATE",
      bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 },
    }] } }));
    await page.goto("/technicians");
    await page.getByRole("button", { name: "+ Add technician" }).click();
    await page.getByLabel("Street address").fill("2125 Crest Ridge Dr");
    await page.getByLabel("city", { exact: true }).fill("Papillion");
    await page.getByLabel("state", { exact: true }).fill("NE");
    await page.getByLabel("Postal code").fill("68133");
    await expect(page.getByRole("button", { name: "Confirm home pin" })).toBeEnabled();
    await page.getByRole("button", { name: "Confirm home pin" }).click();
    await expect(page.getByText("Home pin confirmed")).toBeVisible();
    await page.getByLabel("Street address").fill("2126 Crest Ridge Dr");
    await expect(page.getByText("Home pin confirmed")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Create technician" })).toBeDisabled();
  } finally {
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealer.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
