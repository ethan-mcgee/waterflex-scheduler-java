import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("empty lists disable actions; malformed booking responses stay on the form", async ({ page }) => {
  expect(await prisma.serviceCatalog.count()).toBe(0);
  await page.goto("/book");
  await expect(page.getByRole("button", { name: /see available/i })).toBeDisabled();
  await page.goto("/dispatch/availability");
  await expect(page.getByRole("button", { name: "Save day" })).toBeDisabled();
  await page.goto("/time-off");
  await expect(page.getByRole("button", { name: "Submit time-off request" })).toBeDisabled();
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_NULL_TEST", name: "UI test service", estDurationMin: 60 } });
  try {
    await page.route("**/api/book", route => route.fulfill({ status: 200, contentType: "application/json", body: "null" }));
    await page.goto("/book");
    await page.getByRole("radio").first().check();
    for (const [label, value] of Object.entries({ "First name": "UI", "Last name": "Test", Email: "ui@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68102" }))
      await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
    await page.getByPlaceholder("Street address").fill("1 Main St");
    await page.getByRole("button", { name: /see available/i }).click();
    await expect(page.getByText("Invalid response. Reload before continuing.", { exact: true })).toBeVisible();
    await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
    expect(await prisma.job.count()).toBe(0);
  } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
});

test("dispatch retains missing-location appointments and shows malformed-history errors", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "UI test metro", timezone: "America/Chicago" } });
  const tech = await prisma.technician.create({ data: { metroId: metro.id, name: "UI test tech", homeLat: 41.25, homeLng: -95.93, shiftStartMin: 480, shiftEndMin: 1020 } });
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_LOCATION_TEST", name: "UI location service", estDurationMin: 60 } });
  const customer = await prisma.customer.create({ data: { firstName: "Missing", lastName: "Location", email: "missing@example.invalid", phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "Unverified address", city: "Omaha", state: "NE", postalCode: "68102" } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 60, status: "SCHEDULED" } });
  const start = new Date("2026-10-05T15:00:00Z"), end = new Date("2026-10-05T16:00:00Z");
  const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: tech.id, serviceDate: new Date("2026-10-05T00:00:00Z"), windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: end, sequence: 0 } });
  try {
    await page.route("**/api/dispatch/**", route => route.fulfill({ status: 200, contentType: "application/json", body: "null" }));
    await page.goto("/dispatch?date=2026-10-05");
    await expect(page.getByText("Missing Location", { exact: true })).toBeVisible();
    await expect(page.getByText("Location data missing. Routing is blocked.", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Preview optimization" })).toBeDisabled();
    await expect(page.getByText("Invalid response. Reload before continuing.", { exact: true })).toBeVisible();
  } finally {
    await prisma.appointment.delete({ where: { id: appointment.id } }); await prisma.job.delete({ where: { id: job.id } });
    await prisma.address.delete({ where: { id: address.id } }); await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } }); await prisma.technician.delete({ where: { id: tech.id } }); await prisma.metro.delete({ where: { id: metro.id } });
  }
});
