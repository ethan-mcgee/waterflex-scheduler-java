import { expect, type Page } from "@playwright/test";
import type { createPrismaClient } from "../lib/portalDatabase";
import { DEFAULT_CLIENT_ID } from "../lib/clients";

type Prisma = ReturnType<typeof createPrismaClient>;

/**
 * A metro of the default client with one depot near Omaha. The new work order form lives on the schedule, which needs
 * a metro to open; its depot sits where the booking location fixture places its candidate.
 */
export async function scheduleMetro(prisma: Prisma, name: string): Promise<{ metroId: string; cleanup: () => Promise<void> }> {
  const metro = await prisma.metro.create({ data: { name: `${name} metro`, timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { clientId: DEFAULT_CLIENT_ID, name: `${name} dealership` } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: `${name} depot`, lat: 41.23, lng: -96.18,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "DEPOT", returnTo: "HOME" } } } });
  return { metroId: metro.id, cleanup: async () => {
    await prisma.depotEndpointPolicy.deleteMany({ where: { depotId: depot.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  } };
}

/** Opens the new work order form on the schedule, the way `/work-orders/new` and `/book` now lead there. */
export async function openNewWorkOrder(page: Page, metroId: string) {
  await page.goto(`/work-orders/schedule?metroId=${metroId}&new=1`);
  const drawer = page.getByRole("dialog");
  await expect(drawer.getByRole("heading", { name: "New work order" })).toBeVisible();
  return drawer;
}

/**
 * Answers what "Create and place" starts once a job is taken in: its places by hand and the booking service's
 * recommended times. No scheduler answers in the browser tests, so both say so; the bodies sent are kept.
 */
export async function noScheduler(page: Page) {
  const previews: unknown[] = [], searches: unknown[] = [];
  await page.route("**/api/schedule/placement", async route => {
    previews.push(route.request().postDataJSON());
    await route.fulfill({ status: 503, json: { error: "The scheduler is not running in this test." } });
  });
  await page.route("**/api/book/refresh", async route => {
    searches.push(route.request().postDataJSON());
    await route.fulfill({ status: 503, json: { error: "Online booking is not connected for this client." } });
  });
  return { previews, searches };
}

/** A field of the new work order form by its visible label (the schedule's filter bar has fields of its own). */
export const field = (page: Page, label: string) => page.getByRole("dialog").locator("label").filter({ hasText: new RegExp(`^${label}$`) }).locator("..").locator("input");
