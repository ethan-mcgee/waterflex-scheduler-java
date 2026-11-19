import { test, expect, type Page } from "@playwright/test";
import { z } from "zod";
import { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { required } from "../lib/contracts";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

type Fixture = { leak: string; salt: string; located: string; unlocated: string; cleanup: () => Promise<void> };

/**
 * A metro whose depot covers Omaha, two reason codes (45 and 30 minutes, each with a 10% buffer, so 83 together), and
 * two WaterFlex accounts: one WaterFlex located inside the service area, one it has no coordinates for.
 */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Account browser metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Account browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Account browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const leak = await prisma.serviceCatalog.create({ data: { code: "UI_LEAK", name: "Leaking pipe at the tank", estDurationMin: 45, bufferPct: 0.1 } });
  const salt = await prisma.serviceCatalog.create({ data: { code: "UI_SALT", name: "Salt bridge", estDurationMin: 30, bufferPct: 0.1 } });
  const importedAt = new Date("2026-11-01T12:00:00Z");
  const located = await prisma.account.create({ data: { clientId: DEFAULT_CLIENT_ID, accountNumber: "UI-1001", name: "Ana Ruiz", phone: "4025550111",
    email: "ana@example.invalid", line1: "12 Account St", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.26, lng: -95.94,
    source: "WATERFLEX_IMPORT", importedAt } });
  const unlocated = await prisma.account.create({ data: { clientId: DEFAULT_CLIENT_ID, accountNumber: "UI-1002", name: null, phone: null, email: null,
    line1: "Rural Route 4", city: "Elkhorn", state: "NE", postalCode: "68022", lat: null, lng: null, source: "WATERFLEX_IMPORT", importedAt } });
  return { leak: leak.id, salt: salt.id, located: located.id, unlocated: unlocated.id, cleanup: async () => {
    const customers = await prisma.customer.findMany({ where: { accountId: { in: [located.id, unlocated.id] } }, select: { id: true } });
    const customerIds = customers.map(item => item.id);
    await prisma.job.deleteMany({ where: { customerId: { in: customerIds } } });
    await prisma.address.deleteMany({ where: { customerId: { in: customerIds } } });
    await prisma.customer.deleteMany({ where: { id: { in: customerIds } } });
    await prisma.account.deleteMany({ where: { id: { in: [located.id, unlocated.id] } } });
    await prisma.serviceCatalog.deleteMany({ where: { id: { in: [leak.id, salt.id] } } });
    await prisma.depotEndpointPolicy.deleteMany({ where: { depotId: depot.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

async function fillContact(page: Page) {
  for (const [label, value] of Object.entries({ "First name": "Ana", "Last name": "Ruiz" }))
    await page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input").fill(value);
}

const field = (page: Page, label: string) => page.locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input");

test.describe("a work order for a WaterFlex account", () => {
  let fixture: Fixture;
  test.beforeEach(async () => { fixture = await seed(); });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("two reason codes suggest the time, the dispatcher allots another, and the work order is taken in as asked", async ({ page }) => {
    const sent: unknown[] = [];
    // The real intake runs; the offers are this test's, since no scheduler answers here.
    await page.route("**/api/book", async route => {
      const body = z.object({ requestId: z.string() }).loose().parse(route.request().postDataJSON());
      sent.push(body);
      await route.fetch();
      const job = required(await prisma.job.findUnique({ where: { bookingRequestId: body.requestId } }), "the job the intake took in");
      await route.fulfill({ json: { jobId: job.id, search: { outcome: "AVAILABLE", prescribedSearchCompleted: true, elapsedMs: 1, retryable: false },
        offers: [{ offerId: "account-offer", date: "2099-10-05", windowStart: "2099-10-05T14:00:00Z", windowEnd: "2099-10-05T16:00:00Z", expiresAt: "2099-10-05T13:00:00Z" }] } });
    });
    await page.goto("/work-orders/new");
    await expect(page.getByRole("button", { name: "WaterFlex account" })).toHaveAttribute("aria-pressed", "true");
    await page.getByLabel("Account", { exact: true }).fill("Ruiz");
    await page.getByRole("list", { name: "Matching accounts" }).getByRole("button", { name: /UI-1001/ }).click();
    await expect(page.locator('[data-account="UI-1001"]')).toContainText("12 Account St, Omaha, NE 68102");
    await expect(page.locator('[data-account="UI-1001"]')).toContainText("Located by WaterFlex");
    // The account's phone and email fill the contact; its name is shown, not split into first and last.
    await expect(field(page, "Phone")).toHaveValue("4025550111");
    await expect(field(page, "Email")).toHaveValue("ana@example.invalid");
    await expect(page.getByText("Account name: Ana Ruiz")).toBeVisible();
    await fillContact(page);

    await page.getByLabel("Reason code", { exact: true }).selectOption("UI_LEAK");
    await expect(page.getByLabel("Time allotted (minutes)")).toHaveValue("50");
    await page.getByRole("button", { name: "Add another reason code" }).click();
    // A code already chosen is not offered again.
    await expect(page.getByLabel("Second reason code").locator('option[value="UI_LEAK"]')).toHaveCount(0);
    await page.getByLabel("Second reason code").selectOption("UI_SALT");
    await expect(page.getByLabel("Time allotted (minutes)")).toHaveValue("83");
    await expect(page.getByText("Suggested from the reason codes: 83 min.")).toBeVisible();
    await page.getByLabel("Time allotted (minutes)").fill("721");
    await expect(page.locator("form").getByRole("alert")).toContainText("from 1 to 720");
    await expect(page.getByRole("button", { name: "See times" })).toBeDisabled();
    await page.getByLabel("Time allotted (minutes)").fill("100");
    await page.getByLabel("Work order type").fill("Repair");
    await page.getByLabel("Purchase order").fill("PO-77");
    await page.getByLabel("Call the customer before arriving").check();
    await page.getByLabel("Customer request").fill("Mornings, please");
    await page.getByLabel("Message to the technician").fill("Gate code 1234");

    await page.getByRole("button", { name: "See times" }).click();
    await expect(page.getByRole("heading", { name: "Choose a time" })).toBeVisible();
    await expect(page.getByText("Leaking pipe at the tank +1 more, 100 min. Pick the time the customer wants.")).toBeVisible();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ accountNumber: "UI-1001", serviceCode: "UI_LEAK", extraReasonCodes: ["UI_SALT"], durationMin: 100, callFirst: true });
    expect(sent[0]).not.toHaveProperty("line1");

    const job = required(await prisma.job.findFirst({ where: { customer: { accountId: fixture.located } },
      include: { reasonCodes: true, address: true, customer: true } }), "the account's job");
    expect(job).toMatchObject({ serviceId: fixture.leak, durationMin: 100, suggestedDurationMin: 83, durationOverridden: true, workOrderType: "Repair",
      purchaseOrder: "PO-77", callFirst: true, customerRequest: "Mornings, please", technicianMessage: "Gate code 1234", manualFollowUpStatus: null });
    expect(job.reasonCodes.map(item => [item.position, item.reasonCodeId])).toEqual([[2, fixture.salt]]);
    expect(job.address).toMatchObject({ line1: "12 Account St", lat: 41.26, lng: -95.94, geocodePrecision: "WATERFLEX_ACCOUNT" });
    expect(job.customer).toMatchObject({ firstName: "Ana", lastName: "Ruiz", phone: "4025550111" });
  });

  test("an account WaterFlex has no coordinates for can only be sent to follow-up, and keeps no made-up location", async ({ page }) => {
    await page.goto("/work-orders/new");
    await page.getByLabel("Account", { exact: true }).fill("UI-1002");
    await page.getByRole("list", { name: "Matching accounts" }).getByRole("button", { name: /UI-1002/ }).click();
    await expect(page.locator('[data-account="UI-1002"]').getByRole("status")).toHaveText("WaterFlex has no coordinates for this account. It can only be sent to follow-up.");
    await fillContact(page);
    await field(page, "Email").fill("rural@example.invalid");
    await field(page, "Phone").fill("4025550122");
    await page.getByLabel("Reason code", { exact: true }).selectOption("UI_SALT");
    await expect(page.getByRole("button", { name: "See times" })).toBeDisabled();
    await page.getByRole("button", { name: "Request follow-up" }).click();
    await expect(page.getByRole("heading", { name: "Sent to follow-up" })).toBeVisible();

    const job = required(await prisma.job.findFirst({ where: { customer: { accountId: fixture.unlocated } }, include: { address: true } }), "the follow-up job");
    expect(job).toMatchObject({ manualFollowUpStatus: "PENDING", manualFollowUpReason: "ACCOUNT_UNLOCATED", durationMin: 33, suggestedDurationMin: 33,
      durationOverridden: false, callFirst: false });
    expect(job.address).toMatchObject({ line1: "Rural Route 4", lat: null, lng: null, geocodePrecision: null });
  });
});
