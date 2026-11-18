import { test, expect } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

/** One unlocated request waiting for a call, for the default client. */
async function seed() {
  const service = await prisma.serviceCatalog.create({ data: { code: "UI_FOLLOW_UP", name: "Follow-up test service", estDurationMin: 30 } });
  const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Follow", lastName: "Upcustomer",
    email: "follow@example.invalid", phone: "4025550111" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "1 Unverified Way", city: "Omaha", state: "NE", postalCode: "68102" } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 35,
    manualFollowUpStatus: "PENDING", manualFollowUpReason: "ADDRESS_UNVERIFIED" } });
  return { jobId: job.id, cleanup: async () => {
    await prisma.job.delete({ where: { id: job.id } });
    await prisma.address.delete({ where: { id: address.id } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
  } };
}

test("follow-up lists a waiting request by name, and marks it contacted or shows why it could not", async ({ page }) => {
  const fixture = await seed();
  try {
    await page.goto("/work-orders/follow-up");
    await expect(page.getByRole("heading", { name: "Manual follow-up", level: 1 })).toBeVisible();
    const row = page.locator(`[data-follow-up="${fixture.jobId}"]`);
    await expect(row).toContainText("Follow Upcustomer");
    await expect(row).toContainText("Address not verified");
    const status = row.locator("[data-status]");
    await expect(status).toHaveText("Waiting");
    // An unlocated request cannot be placed on the calendar until someone confirms where it is.
    await expect(row.getByRole("link", { name: "Place on calendar" })).toHaveCount(0);

    await page.route("**/api/dispatch/follow-up", route => route.fulfill({ status: 500, contentType: "application/json", body: "{}" }));
    await row.getByRole("button", { name: "Contacted" }).click();
    await expect(row.getByRole("alert")).toHaveText("Could not update follow-up");
    await expect(status).toHaveText("Waiting");
    await page.unroute("**/api/dispatch/follow-up");

    await row.getByRole("button", { name: "Contacted" }).click();
    await expect(status).toHaveText("Contacted");
    await expect(status).toHaveAttribute("data-status", "CONTACTED");
    await expect(row.getByRole("alert")).toHaveCount(0);
    await expect.poll(async () => (await prisma.job.findUniqueOrThrow({ where: { id: fixture.jobId } })).manualFollowUpStatus).toBe("CONTACTED");
  } finally {
    await fixture.cleanup();
  }
});

test("follow-up says when nothing is waiting", async ({ page }) => {
  await page.goto("/work-orders/follow-up");
  await expect(page.getByRole("main").getByRole("status")).toContainText("No requests are waiting");
});
