import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { initialAvailability } from "../lib/technicianAvailability";
import { DEFAULT_CLIENT_ID } from "../lib/clients";
import { required } from "../lib/contracts";

const prisma = createPrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

const DATE = "2026-10-05"; // a Monday; America/Chicago is UTC-5, so 15:00 UTC is 10 AM
const utc = (hour: number) => `${DATE}T${String(hour).padStart(2, "0")}:00:00.000Z`;

type Fixture = { metroId: string; visit: string; visitJob: string; waitingJob: string; salt: string; cleanup: () => Promise<void> };

/**
 * One technician with a booked visit (a leak call, taken in as a 50-minute suggestion, marked call first) and a job
 * waiting for the calendar (a salt bridge, taken in before reason codes, so with no suggestion).
 */
async function seed(): Promise<Fixture> {
  const metro = await prisma.metro.create({ data: { name: "Panel browser metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Panel browser dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Panel browser depot", lat: 41.25, lng: -95.93,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { clientId: DEFAULT_CLIENT_ID, name: "Panel Tech", color: "#dc2626",
    homeLat: 41.3, homeLng: -95.95, shiftStartMin: 480, shiftEndMin: 1020, availabilityVersions: initialAvailability(480, 1020),
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } } } });
  const leak = await prisma.serviceCatalog.create({ data: { code: "UI_PANEL_LEAK", name: "Panel leak", estDurationMin: 45, bufferPct: 0.1 } });
  const salt = await prisma.serviceCatalog.create({ data: { code: "UI_PANEL_SALT", name: "Panel salt bridge", estDurationMin: 30, bufferPct: 0 } });
  const customer = await prisma.customer.create({ data: { clientId: DEFAULT_CLIENT_ID, firstName: "Panel", lastName: "Customer", email: "panel@example.invalid", phone: "4025550100" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "1 Panel St", city: "Omaha", state: "NE", postalCode: "68102",
    lat: 41.27, lng: -95.9, geocodePrecision: "ROOFTOP" } });
  const visitJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: leak.id, durationMin: 50,
    suggestedDurationMin: 50, durationOverridden: false, callFirst: true, workOrderType: "Repair", status: "SCHEDULED" } });
  const visit = await prisma.appointment.create({ data: { jobId: visitJob.id, technicianId: technician.id, serviceDate: new Date(`${DATE}T00:00:00Z`),
    windowStart: new Date(utc(15)), windowEnd: new Date(utc(17)), plannedStart: new Date(utc(15)), plannedEnd: new Date(utc(16)), sequence: 0 } });
  const waitingJob = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: salt.id, durationMin: 40, status: "PENDING" } });
  return { metroId: metro.id, visit: visit.id, visitJob: visitJob.id, waitingJob: waitingJob.id, salt: salt.id, cleanup: async () => {
    await prisma.appointment.deleteMany({ where: { id: visit.id } });
    await prisma.job.deleteMany({ where: { id: { in: [visitJob.id, waitingJob.id] } } });
    await prisma.address.delete({ where: { id: address.id } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.deleteMany({ where: { id: { in: [leak.id, salt.id] } } });
    await prisma.technicianDepotAssignment.deleteMany({ where: { technicianId: technician.id } });
    await prisma.technicianAvailabilityVersion.deleteMany({ where: { technicianId: technician.id } });
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depotEndpointPolicy.deleteMany({ where: { depotId: depot.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

const panel = (page: Page) => page.getByRole("complementary", { name: "Place a job" });

test.describe("the work order panel", () => {
  let fixture: Fixture;
  test.beforeEach(async ({ page }) => { fixture = await seed(); await page.setViewportSize({ width: 1600, height: 1400 }); });
  test.afterEach(async () => { await fixture.cleanup(); });

  test("a booked visit shows its work order, and its details can be edited while its codes and time stay as booked", async ({ page }) => {
    await page.route("**/api/schedule/placement", route => route.fulfill({ status: 503, json: { error: "Scheduling is not connected." } }));
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    // A click picks the visit up to move it; its details are a click away in the column.
    await page.locator(`[data-schedule-view="day"] [data-appointment-id="${fixture.visit}"]`).click();
    await panel(page).getByRole("button", { name: "Details" }).click();
    const dialog = page.getByRole("dialog");
    const workOrder = dialog.locator(`[data-work-order="${fixture.visitJob}"]`);
    await expect(workOrder).toContainText("Panel leak (UI_PANEL_LEAK)");
    await expect(workOrder).toContainText("50 min, suggested by the reason codes");
    await expect(workOrder).toContainText("Repair");
    await expect(workOrder.locator("dt", { hasText: "Call first" }).locator("+ dd")).toHaveText("Yes");
    await expect(workOrder.locator("dt", { hasText: "Purchase order" }).locator("+ dd")).toHaveText("None");

    await workOrder.getByRole("button", { name: "Edit work order" }).click();
    const form = dialog.getByRole("form", { name: "Edit work order" });
    await expect(form).toContainText("A booked visit keeps its reason codes and time allotted");
    await expect(form.getByLabel("Reason code", { exact: true })).toHaveCount(0);
    await form.getByLabel("Purchase order").fill("PO-9");
    await form.getByLabel("Call first").selectOption("no");
    await form.getByLabel("Message to the technician").fill("Dog in the yard");
    await form.getByLabel("Work order type").fill("");
    await form.getByRole("button", { name: "Save work order" }).click();

    await expect(workOrder.locator("dt", { hasText: "Purchase order" }).locator("+ dd")).toHaveText("PO-9");
    await expect(workOrder.locator("dt", { hasText: "Call first" }).locator("+ dd")).toHaveText("No");
    await expect(workOrder.locator("dt", { hasText: "Work order type" }).locator("+ dd")).toHaveText("None");
    const job = required(await prisma.job.findUnique({ where: { id: fixture.visitJob } }), "the visit's job");
    expect(job).toMatchObject({ purchaseOrder: "PO-9", callFirst: false, technicianMessage: "Dog in the yard", workOrderType: null, durationMin: 50 });

    // The portal refuses a change of codes or time on a booked visit, whatever sends it.
    const refused = await page.request.patch(`/api/work-orders/${fixture.visitJob}`, { data: { workOrderType: null, purchaseOrder: null,
      technicianMessage: null, customerRequest: null, callFirst: null, timing: { reasonCodes: ["UI_PANEL_SALT"], durationMin: null } } });
    expect(refused.status()).toBe(409);
    expect((await prisma.job.findUnique({ where: { id: fixture.visitJob } }))?.durationMin).toBe(50);
  });

  test("a waiting job's codes and time can be changed while it is placed, and a job taken in before codes says so", async ({ page }) => {
    // No scheduler answers here; the job stays open in the column with its work order beside it.
    await page.route("**/api/schedule/placement", route => route.fulfill({ status: 503, json: { error: "Scheduling is not connected." } }));
    await page.goto(`/work-orders/schedule?metroId=${fixture.metroId}&date=${DATE}`);
    await panel(page).locator(`[data-job-id="${fixture.waitingJob}"]`).click();
    const workOrder = panel(page).locator(`[data-work-order="${fixture.waitingJob}"]`);
    await expect(workOrder).toContainText("40 min (taken in before reason codes)");
    await expect(workOrder.locator("dt", { hasText: "Call first" }).locator("+ dd")).toHaveText("Not recorded");

    await workOrder.getByRole("button", { name: "Edit work order" }).click();
    const form = panel(page).getByRole("form", { name: "Edit work order" });
    // A job taken in before reason codes offers its code's suggestion, not its old time.
    await expect(form.getByLabel("Time allotted (minutes)")).toHaveValue("30");
    await form.getByRole("button", { name: "Add another reason code" }).click();
    await form.getByLabel("Reason code 2").selectOption("UI_PANEL_LEAK");
    await expect(form.getByLabel("Time allotted (minutes)")).toHaveValue("80");
    await form.getByLabel("Time allotted (minutes)").fill("90");
    await form.getByRole("button", { name: "Save work order" }).click();

    await expect(workOrder).toContainText("90 min, set by the dispatcher (suggested 80 min)");
    await expect(workOrder).toContainText("Panel leak (UI_PANEL_LEAK)");
    await expect(panel(page).locator(`[data-job-id="${fixture.waitingJob}"]`)).toContainText("Panel salt bridge +1 more, 90 min");
    const job = required(await prisma.job.findUnique({ where: { id: fixture.waitingJob }, include: { reasonCodes: true } }), "the waiting job");
    expect(job).toMatchObject({ serviceId: fixture.salt, durationMin: 90, suggestedDurationMin: 80, durationOverridden: true, callFirst: null });
    expect(job.reasonCodes.map(item => item.position)).toEqual([2]);
  });
});
