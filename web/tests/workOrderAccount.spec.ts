import { test, expect, type Page } from "@playwright/test";
import { z } from "zod";
import { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { required } from "../lib/contracts";
import { field, noScheduler, openNewWorkOrder } from "./newWorkOrderPage";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

type Fixture = { metroId: string; leak: string; salt: string; located: string; unlocated: string; cleanup: () => Promise<void> };

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
  return { metroId: metro.id, leak: leak.id, salt: salt.id, located: located.id, unlocated: unlocated.id, cleanup: async () => {
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
    await field(page, label).fill(value);
}


test.describe("a work order for a WaterFlex account", () => {
  let fixture: Fixture;
  test.beforeEach(async () => { fixture = await seed(); });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("two reason codes suggest the time, the dispatcher allots another, and the work order is taken in as asked", async ({ page }) => {
    const sent: unknown[] = [];
    // The real intake runs; what it starts afterwards asks a scheduler, which does not answer here.
    await page.route("**/api/schedule/placement/job", async route => {
      sent.push(z.object({ requestId: z.string() }).loose().parse(route.request().postDataJSON()));
      await route.continue();
    });
    const placing = await noScheduler(page);
    const drawer = await openNewWorkOrder(page, fixture.metroId);
    await expect(page.getByRole("button", { name: "WaterFlex account" })).toHaveAttribute("aria-pressed", "true");
    await drawer.getByLabel("Account", { exact: true }).fill("Ruiz");
    await page.getByRole("list", { name: "Matching accounts" }).getByRole("button", { name: /UI-1001/ }).click();
    await expect(page.locator('[data-account="UI-1001"]')).toContainText("12 Account St, Omaha, NE 68102");
    await expect(page.locator('[data-account="UI-1001"]')).toContainText("Located by WaterFlex");
    // The account's phone and email fill the contact; its name is shown, not split into first and last.
    await expect(field(page, "Phone")).toHaveValue("4025550111");
    await expect(field(page, "Email")).toHaveValue("ana@example.invalid");
    await expect(page.getByText("Account name: Ana Ruiz")).toBeVisible();
    await fillContact(page);

    await drawer.getByLabel("Reason code", { exact: true }).selectOption("UI_LEAK");
    await expect(drawer.getByLabel("Time allotted (minutes)")).toHaveValue("50");
    await page.getByRole("button", { name: "Add another reason code" }).click();
    // A code already chosen is not offered again.
    await expect(drawer.getByLabel("Second reason code").locator('option[value="UI_LEAK"]')).toHaveCount(0);
    await drawer.getByLabel("Second reason code").selectOption("UI_SALT");
    await expect(drawer.getByLabel("Time allotted (minutes)")).toHaveValue("83");
    await expect(page.getByText("Suggested from the reason codes: 83 min.")).toBeVisible();
    await drawer.getByLabel("Time allotted (minutes)").fill("721");
    await expect(drawer.getByRole("alert")).toContainText("from 1 to 720");
    await expect(drawer.getByRole("button", { name: "Create and place" })).toBeDisabled();
    await drawer.getByLabel("Time allotted (minutes)").fill("100");
    await drawer.getByLabel("Work order type").fill("Repair");
    await drawer.getByLabel("Purchase order").fill("PO-77");
    await drawer.getByLabel("Call the customer before arriving").check();
    await drawer.getByLabel("Customer request").fill("Mornings, please");
    await drawer.getByLabel("Message to the technician").fill("Gate code 1234");

    await drawer.getByRole("button", { name: "Create and place" }).click();
    // The form closes and the job is being placed: its recommended times and its places by hand are asked for.
    await expect(page.getByRole("dialog")).toHaveCount(0);
    const column = page.getByRole("complementary", { name: "Place a job" });
    await expect(column.getByRole("heading", { name: "Ana Ruiz, Leaking pipe at the tank" })).toBeVisible();
    await expect(column.locator('[data-recommended-times="failed"]')).toContainText("Online booking is not connected for this client.");
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
    expect(placing.searches).toEqual([{ jobId: job.id }]);
    expect(placing.previews).toEqual([expect.objectContaining({ metroId: fixture.metroId, jobId: job.id })]);
    // The new job waits in Unassigned too.
    await expect(column.locator(`[data-job-id="${job.id}"]`)).toBeVisible();
  });

  test("an account WaterFlex has no coordinates for can only be sent to follow-up, and keeps no made-up location", async ({ page }) => {
    const drawer = await openNewWorkOrder(page, fixture.metroId);
    await drawer.getByLabel("Account", { exact: true }).fill("UI-1002");
    await page.getByRole("list", { name: "Matching accounts" }).getByRole("button", { name: /UI-1002/ }).click();
    await expect(page.locator('[data-account="UI-1002"]').getByRole("status")).toHaveText("WaterFlex has no coordinates for this account. It can only be sent to follow-up.");
    await fillContact(page);
    await field(page, "Email").fill("rural@example.invalid");
    await field(page, "Phone").fill("4025550122");
    await drawer.getByLabel("Reason code", { exact: true }).selectOption("UI_SALT");
    await expect(drawer.getByRole("button", { name: "Create and place" })).toBeDisabled();
    await drawer.getByRole("button", { name: "Request follow-up" }).click();
    await expect(page.locator("[data-placement-saved]")).toContainText("Sent to follow-up: Ana Ruiz, Salt bridge waits in Unassigned");

    const job = required(await prisma.job.findFirst({ where: { customer: { accountId: fixture.unlocated } }, include: { address: true } }), "the follow-up job");
    expect(job).toMatchObject({ manualFollowUpStatus: "PENDING", manualFollowUpReason: "ACCOUNT_UNLOCATED", durationMin: 33, suggestedDurationMin: 33,
      durationOverridden: false, callFirst: false });
    expect(job.address).toMatchObject({ line1: "Rural Route 4", lat: null, lng: null, geocodePrecision: null });
  });
});
