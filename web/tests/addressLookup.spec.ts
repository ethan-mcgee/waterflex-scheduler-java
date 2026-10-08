import { expect, test } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });
for (const form of ["technician", "depot", "edit"] as const) {
  test(`${form} lookup preserves specific errors and drafts and discards stale responses`, async ({ page }) => {
    const metro = await prisma.metro.create({ data: { name: `Lookup ${form} metro`, timezone: "America/Chicago" } });
    const dealer = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: `Lookup ${form} dealer` } });
    const depot = await prisma.depot.create({ data: { name: `Lookup ${form} depot`, metroId: metro.id, dealershipId: dealer.id, lat: 41.23, lng: -96.18,
      endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
    let release: (() => void) | undefined;
    try {
      await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ status: 503 }));
      await page.route("http://localhost:8083/omaha/**/*.mvt", route => route.fulfill({ status: 200, body: "" }));
      let delayed = false;
      let status = 504;
      let message = "Address lookup timed out. Try again.";
      await page.route("**/api/technicians/geocode", async route => {
        if (status === 200) return route.fulfill({ json: { candidates: [{ lat: 41.23, lng: -96.18, precision: "ROOFTOP" }] } });
        const responseStatus = status, responseMessage = message;
        if (delayed) await new Promise<void>(resolve => { release = resolve; });
        try { await route.fulfill({ status: responseStatus, json: { error: responseMessage } }); } catch { /* Address edit aborted this request. */ }
      });
      await page.goto(form === "technician" ? "/technicians" : "/dealerships");
      if (form === "technician") await page.getByRole("button", { name: "+ Add technician" }).click();
      if (form === "edit") await page.getByRole("button", { name: "Edit details" }).last().click();
      if (form === "depot") await page.getByLabel("Depot name").fill("Retained lookup depot");
      const street = page.getByLabel("Street address").last();
      const zip = page.getByLabel("Postal code").last();
      await street.fill("2825 S 170th Plz");
      await page.getByLabel(/^city$/i).last().fill("Omaha");
      await page.getByLabel(/^state$/i).last().fill("NE");
      await zip.fill("68130");
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      await expect(street).toHaveValue("2825 S 170th Plz");
      status = 503; message = "Address lookup is unavailable. Try again later.";
      await street.fill("2825 South 170th Plaza");
      await expect(page.getByText("Address lookup timed out. Try again.", { exact: true })).toHaveCount(0);
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      status = 502; message = "Address lookup returned invalid data. Try again later.";
      await street.fill("2825 S 170th Plaza");
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      delayed = true;
      await street.fill("2825 South 170th Plz");
      await expect.poll(() => release != null).toBe(true);
      status = 200; delayed = false;
      await street.fill("2825 S 170th Plz");
      release?.();
      await expect(page.getByText(message, { exact: true })).toHaveCount(0);
      if (form === "edit") {
        await page.getByRole("button", { name: "Confirm pin", exact: true }).click();
        await expect(page.getByText("Pin confirmed", { exact: true })).toBeVisible();
      } else if (form === "depot") await expect(page.getByText("Pin: 41.230000, -96.180000")).toBeVisible();
      else await expect(page.getByText("Review the located home pin. Drag it to adjust the location.")).toBeVisible();
      status = 404; message = "No verified pin found for this address.";
      await zip.fill("68103");
      await expect(page.getByText("Pin confirmed", { exact: true })).toHaveCount(0);
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      await expect(page.getByRole("button", { name: form === "edit" ? "Save depot details" : form === "depot" ? "Create depot" : "Create technician", exact: true })).toBeDisabled();
      await expect(street).toHaveValue("2825 S 170th Plz");
    } finally {
      release?.();
      await prisma.depot.delete({ where: { id: depot.id } });
      await prisma.dealership.delete({ where: { id: dealer.id } });
      await prisma.metro.delete({ where: { id: metro.id } });
    }
  });
}

test("booking displays lookup failures and retains the submitted address", async ({ page }) => {
  const service = await prisma.serviceCatalog.create({ data: { code: `LOOKUP_${Date.now()}`, name: "Lookup browser service", estDurationMin: 60 } });
  try {
    let message = "Address lookup timed out. Try again.";
    await page.route("**/api/book/location", route => route.fulfill({ status: 504, json: { error: message } }));
    await page.goto("/book");
    await page.getByRole("radio", { name: /Lookup browser service/ }).check();
    for (const [label, value] of Object.entries({ "First name": "Lookup", "Last name": "Fixture", Email: "lookup@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68130" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("2825 S 170th Plz");
    for (const failure of ["Address lookup timed out. Try again.", "Address lookup is unavailable. Try again later.", "Address lookup returned invalid data. Try again later."]) {
      message = failure;
      await page.getByRole("button", { name: /review service location/i }).click();
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      await expect(page.getByPlaceholder("Street address")).toHaveValue("2825 S 170th Plz");
    }
  } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
});
