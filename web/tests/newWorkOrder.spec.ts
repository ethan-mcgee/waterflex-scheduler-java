import { test, expect, type Page } from "@playwright/test";
import { createPrismaClient } from "../lib/portalDatabase";
import { bookingAddress, bookingRequest } from "../lib/contracts";
import { locationFixture, mockBookingLocation } from "./bookingLocationFixture";
import { field, noScheduler, openNewWorkOrder, scheduleMetro } from "./newWorkOrderPage";

const prisma = createPrismaClient();
test.afterAll(() => prisma.$disconnect());

/** Fills the typed-address form: one reason code, the contact and the address. */
async function fillAddress(page: Page, code: string, contact: Record<string, string>, line1: string) {
  await page.getByRole("dialog").getByLabel("Reason code", { exact: true }).selectOption(code);
  for (const [label, value] of Object.entries(contact)) await field(page, label).fill(value);
  await page.getByPlaceholder("Street address").fill(line1);
}

const CONTACT = { "First name": "Pin", "Last name": "Test", Email: "pin@example.invalid", Phone: "4025550100", City: "Omaha", State: "NE", ZIP: "68130" };

test.describe("the new work order form on the schedule", () => {
  let metro: Awaited<ReturnType<typeof scheduleMetro>>;
  test.beforeEach(async () => { metro = await scheduleMetro(prisma, "New work order"); });
  test.afterEach(async () => { await metro.cleanup(); });

  for (const hasMatch of [true, false]) {
    test(`a typed address is placed by hand on the map with ${hasMatch ? "an exact match to correct" : "no candidate"}, and goes to follow-up when the map is out`, async ({ page }) => {
      const service = await prisma.serviceCatalog.create({ data: { code: `PIN_${hasMatch}`, name: "Pin service", estDurationMin: 30 } });
      try {
        await mockBookingLocation(page);
        await page.route("**/api/book/location", route => route.fulfill({ json: { ...locationFixture,
          status: hasMatch ? "MATCHED" : "NO_MATCH", candidates: hasMatch ? locationFixture.candidates : [] } }));
        let created = 0;
        await page.route("**/api/schedule/placement/job", async route => {
          const body = bookingRequest.parse(route.request().postDataJSON()); created++;
          expect(body.confirmedPin?.manuallyConfirmed).toBe(true);
          expect(body.confirmedPin?.lat).not.toBe(locationFixture.candidates[0]?.lat);
          await route.fulfill({ status: 422, json: { status: "UNROUTABLE", error: "Place the pin at your driveway entrance." } });
        });
        const drawer = await openNewWorkOrder(page, metro.metroId);
        await fillAddress(page, service.code, CONTACT, "123 Cascio Drive");
        await drawer.getByRole("button", { name: "Review service location" }).click();
        await expect(drawer.getByRole("heading", { name: "Confirm the service location" })).toBeVisible();
        const confirm = drawer.getByRole("button", { name: "Confirm pin, create and place" });
        const map = drawer.getByLabel("Service location map");
        await expect(map.locator("canvas")).toBeVisible();
        if (!hasMatch) await expect(confirm).toBeDisabled();
        else await expect(confirm).toBeEnabled();
        await map.click({ position: { x: 150, y: 200 } });
        await expect(confirm).toBeEnabled();
        expect(created).toBe(0);
        await confirm.click();
        await expect(drawer.getByRole("alert")).toHaveText("Place the pin at your driveway entrance.");
        expect(created).toBe(1);
        await drawer.getByRole("button", { name: "Edit address" }).click();
        await page.getByPlaceholder("Street address").fill("125 Cascio Drive");
        await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ status: 503 }));
        await drawer.getByRole("button", { name: "Review service location" }).click();
        await expect(confirm).toBeDisabled();
        await expect(drawer.getByText(/Pin confirmation is blocked/)).toBeVisible();
        await page.route("**/api/schedule/placement/job", async route => {
          const body = bookingRequest.parse(route.request().postDataJSON());
          expect(body.followUp).toBe(true); expect(body.confirmedPin).toBeUndefined();
          await route.fulfill({ json: { jobId: "follow-up", followUp: true } });
        });
        await drawer.getByRole("button", { name: "Request follow-up" }).click();
        // The form closes; the work order waits on its call, so nothing is placed.
        await expect(page.getByRole("dialog")).toHaveCount(0);
        await expect(page.locator("[data-placement-saved]")).toContainText("Sent to follow-up: Pin Test, Pin service waits in Unassigned and on the Follow-up page");
        await expect(page.getByRole("complementary", { name: "Place a job" }).getByRole("heading", { name: "Pin Test, Pin service" })).toHaveCount(0);
      } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
    });
  }

  test("address edits abort the lookup, so a stale result never replaces a newer address", async ({ page }) => {
    const service = await prisma.serviceCatalog.create({ data: { code: "PIN_STALE", name: "Stale pin service", estDurationMin: 30 } });
    let release: (() => void) | undefined;
    try {
      await mockBookingLocation(page);
      await page.route("**/api/book/location", async route => {
        const address = bookingAddress.parse(route.request().postDataJSON());
        if (address.line1 === "Old address") await new Promise<void>(resolve => { release = resolve; });
        try { await route.fulfill({ json: locationFixture }); } catch { /* An edited address cancels this response. */ }
      });
      const drawer = await openNewWorkOrder(page, metro.metroId);
      await fillAddress(page, service.code, CONTACT, "Old address");
      await drawer.getByRole("button", { name: "Review service location" }).click();
      await expect.poll(() => release !== undefined).toBe(true);
      await page.getByPlaceholder("Street address").fill("New address");
      await drawer.getByRole("button", { name: "Review service location" }).click();
      await expect(drawer.getByText("New address, Omaha, NE 68130")).toBeVisible();
      release?.();
      await expect(drawer.getByText("New address, Omaha, NE 68130")).toBeVisible();
      await expect(drawer.getByText("Old address, Omaha, NE 68130")).toHaveCount(0);
    } finally { release?.(); await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
  });

  test("a failed create keeps the form and is sent again as the same request, and an edit makes it a new one", async ({ page }) => {
    const service = await prisma.serviceCatalog.create({ data: { code: "UI_CREATE_RETRY", name: "Retry service", estDurationMin: 60 } });
    let finish: (() => void) | undefined;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    const requestIds: string[] = [];
    try {
      await mockBookingLocation(page);
      await page.route("**/api/schedule/placement/job", async route => {
        requestIds.push(bookingRequest.parse(route.request().postDataJSON()).requestId);
        if (requestIds.length === 1) await pending;
        await route.fulfill({ status: 503, json: { error: "Scheduling service unavailable" } });
      });
      const drawer = await openNewWorkOrder(page, metro.metroId);
      await fillAddress(page, service.code, { ...CONTACT, "First name": "Keep", "Last name": "Details", ZIP: "68102" }, "1 Main St");
      await drawer.getByRole("button", { name: "Review service location" }).click();
      const confirm = drawer.getByRole("button", { name: "Confirm pin, create and place" });
      await confirm.click();
      await expect(drawer.getByRole("status").filter({ hasText: "Creating the work order..." })).toBeVisible();
      await expect(drawer.getByRole("button", { name: "Close new work order" })).toBeDisabled();
      finish?.();
      await expect(drawer.getByRole("alert")).toHaveText("Scheduling service unavailable");
      await expect(drawer.getByText("1 Main St, Omaha, NE 68102")).toBeVisible();
      await expect(confirm).toBeEnabled();

      // Sent again unchanged, it is the same request, so a create that did land is not made twice.
      await confirm.click();
      await expect.poll(() => requestIds.length).toBe(2);
      expect(requestIds[1]).toBe(requestIds[0]);
      // An edited work order is another request.
      await drawer.getByRole("button", { name: "Edit address" }).click();
      await expect(page.getByPlaceholder("Street address")).toHaveValue("1 Main St");
      await expect(field(page, "First name")).toHaveValue("Keep");
      await page.getByPlaceholder("Street address").fill("2 Main St");
      await drawer.getByRole("button", { name: "Review service location" }).click();
      await confirm.click();
      await expect.poll(() => requestIds.length).toBe(3);
      expect(requestIds[2]).not.toBe(requestIds[0]);

    } finally {
      finish?.();
      await prisma.serviceCatalog.delete({ where: { id: service.id } });
    }
  });

  test("address lookup failures are said as they are, and the address typed is kept", async ({ page }) => {
    const service = await prisma.serviceCatalog.create({ data: { code: `LOOKUP_${Date.now()}`, name: "Lookup browser service", estDurationMin: 60 } });
    try {
      let message = "Address lookup timed out. Try again.";
      await page.route("**/api/book/location", route => route.fulfill({ status: 504, json: { error: message } }));
      const drawer = await openNewWorkOrder(page, metro.metroId);
      await fillAddress(page, service.code, { ...CONTACT, "First name": "Lookup", "Last name": "Fixture" }, "2825 S 170th Plz");
      for (const failure of ["Address lookup timed out. Try again.", "Address lookup is unavailable. Try again later.", "Address lookup returned invalid data. Try again later."]) {
        message = failure;
        await drawer.getByRole("button", { name: "Review service location" }).click();
        await expect(drawer.getByText(message, { exact: true })).toBeVisible();
        await expect(page.getByPlaceholder("Street address")).toHaveValue("2825 S 170th Plz");
      }
    } finally { await prisma.serviceCatalog.delete({ where: { id: service.id } }); }
  });

  test("the form opens from the Unassigned column, closes with Escape, and a closed ?new=1 link does not open it again", async ({ page }) => {
    await noScheduler(page);
    await page.goto(`/work-orders/schedule?metroId=${metro.metroId}`);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByRole("complementary", { name: "Place a job" }).getByRole("button", { name: "New work order" }).click();
    await expect(page.getByRole("dialog").getByRole("heading", { name: "New work order" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);

    await openNewWorkOrder(page, metro.metroId);
    await page.getByRole("button", { name: "Close new work order" }).click();
    await expect(page).not.toHaveURL(/new=1/);
    await page.reload();
    await expect(page.getByRole("heading", { name: "Work Orders Schedule" })).toBeVisible();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});
