import { test, expect } from "@playwright/test";
import { PrismaClient } from "@prisma/client";
import { initialAvailability, nextTemplateEffectiveDate } from "../lib/technicianAvailability";
import { addCalendarDays, localMidnightUtc } from "../lib/date";

const prisma = new PrismaClient();
test.afterAll(async () => { await prisma.$disconnect(); });

test("legacy contacts stay nullable and profile and pending week persist after refresh", async ({ page }) => {
  const metro = await prisma.metro.create({ data: { name: "Profile UI metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Profile dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Profile depot", lat: 41.2, lng: -95.9,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const nextDepot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Profile destination", lat: 41.2, lng: -95.9,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { name: "Profile Fixture", color: "#2563eb",
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
    homeLat: 41.2, homeLng: -95.9, shiftStartMin: 480, shiftEndMin: 1020,
    availabilityVersions: initialAvailability(480, 1020) } });
  try {
    await page.goto("/technicians");
    for (const width of [1280, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByText("Current weekly availability", { exact: true })).toBeVisible();
      await expect(page.getByText("Upcoming schedule exceptions", { exact: true })).toBeVisible();
      await expect(page.getByText("This week", { exact: true })).toHaveCount(0);
      const qualifications = await page.locator('[class*="profileQualifications"]').boundingBox();
      const availability = await page.getByText("Current weekly availability", { exact: true }).boundingBox();
      expect(qualifications).not.toBeNull();
      expect(availability).not.toBeNull();
      if (qualifications && availability) expect(availability.y - (qualifications.y + qualifications.height)).toBeGreaterThanOrEqual(24);
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await expect(page.getByText("No email on file")).toBeVisible();
    await expect(page.locator("#move-date")).toHaveCount(0);
    await page.getByRole("button", { name: "Edit profile" }).click();
    await expect(page.locator("#move-date")).toBeVisible();
    let movePayload: unknown = null;
    await page.route(`**/api/technicians/${technician.id}/depot-assignments`, route => {
      movePayload = route.request().postDataJSON() as unknown;
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ success: true }) });
    });
    const moveDate = addCalendarDays(new Intl.DateTimeFormat("en-CA", { timeZone: "America/Chicago", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date()), 30);
    await page.locator("#move-date").fill(moveDate);
    await page.locator("#move-depot").selectOption(nextDepot.id);
    await page.getByRole("button", { name: "Schedule depot move" }).click();
    await expect(page.getByText("Depot assignment scheduled.")).toBeVisible();
    expect(movePayload).toEqual({ depotId: nextDepot.id, effectiveDate: moveDate });
    await page.locator("#profile-name").fill("Updated Fixture");
    await page.getByRole("button", { name: "Use color #dc2626" }).click();
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByText("Profile saved.")).toBeVisible();
    await page.reload();
    await expect(page.getByText("Updated Fixture").first()).toBeVisible();
    await expect(page.getByText("No email on file")).toBeVisible();
    const saved = await prisma.technician.findUniqueOrThrow({ where: { id: technician.id } });
    expect(saved.color).toBe("#dc2626");
    expect(saved.email).toBeNull();
    expect(saved.phone).toBeNull();

    await page.goto("/dispatch");
    await expect(page.locator('[class*="columnHeader"]').filter({ hasText: "Updated Fixture" })).toHaveCSS("border-top-color", "rgb(220, 38, 38)");
    await page.goto("/schedule");
    await expect(page.locator('[class*="techHeader"]').filter({ hasText: "Updated Fixture" })).toHaveCSS("border-left-color", "rgb(220, 38, 38)");
    await page.goto("/technicians");

    await page.getByRole("button", { name: "Edit profile" }).click();
    await page.getByRole("checkbox", { name: "Sat" }).last().check();
    await page.getByLabel("Sat start").fill("10:00");
    await page.getByLabel("Sat end").fill("14:00");
    await page.getByRole("button", { name: "Save weekly availability" }).click();
    await expect(page.getByText("Weekly availability scheduled.")).toBeVisible();
    await page.reload();
    await expect(page.getByText(/replacement weekly schedule is pending/)).toBeVisible();
    await page.getByRole("button", { name: "Edit profile" }).click();
    await expect(page.getByText(/Pending replacement starts/)).toBeVisible();
    await page.getByRole("checkbox", { name: "Sun" }).last().check();
    await page.getByRole("button", { name: "Save weekly availability" }).click();
    await expect(page.getByText("Weekly availability scheduled.")).toBeVisible();
    const versions = await prisma.technicianAvailabilityVersion.findMany({ where: { technicianId: technician.id }, include: { days: true } });
    expect(versions).toHaveLength(2);
    const pending = versions.find(version => version.effectiveDate.toISOString().slice(0, 10) === nextTemplateEffectiveDate());
    expect(pending?.days.find(day => day.dayOfWeek === 0)?.available).toBe(true);
    expect(pending?.days.find(day => day.dayOfWeek === 6)?.shiftStartMin).toBe(600);
  } finally {
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.depot.delete({ where: { id: nextDepot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});

test("weekly edits reject malformed payloads and conflicts with an appointment", async ({ request }) => {
  const metro = await prisma.metro.create({ data: { name: "Profile conflict metro", timezone: "America/Chicago" } });
  const dealership = await prisma.dealership.create({ data: { name: "Conflict dealership" } });
  const depot = await prisma.depot.create({ data: { metroId: metro.id, dealershipId: dealership.id, name: "Conflict depot", lat: 41.2, lng: -95.9,
    endpointPolicies: { create: { effectiveDate: new Date("1900-01-01T00:00:00Z"), departure: "HOME", returnTo: "HOME" } } } });
  const technician = await prisma.technician.create({ data: { name: "Conflict Fixture", color: "#2563eb",
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
    homeLat: 41.2, homeLng: -95.9, shiftStartMin: 480, shiftEndMin: 1020,
    availabilityVersions: initialAvailability(480, 1020) } });
  const missingWeek = await prisma.technician.create({ data: { name: "Missing week fixture", color: "#2563eb",
    depotAssignments: { create: { depotId: depot.id, effectiveDate: new Date("1900-01-01T00:00:00Z") } },
    homeLat: 41.2, homeLng: -95.9, shiftStartMin: 480, shiftEndMin: 1020 } });
  const service = await prisma.serviceCatalog.create({ data: { code: `PROFILE_${technician.id}`, name: "Profile fixture service", estDurationMin: 60 } });
  const customer = await prisma.customer.create({ data: { firstName: "Profile", lastName: "Fixture", email: "profile@example.invalid", phone: "4025550123" } });
  const address = await prisma.address.create({ data: { customerId: customer.id, line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102", lat: 41.2, lng: -95.9 } });
  const job = await prisma.job.create({ data: { customerId: customer.id, addressId: address.id, serviceId: service.id, durationMin: 60, status: "SCHEDULED" } });
  let date = nextTemplateEffectiveDate();
  while (new Date(`${date}T00:00:00Z`).getUTCDay() !== 1) date = addCalendarDays(date, 1);
  const start = new Date(localMidnightUtc(date, "America/Chicago").getTime() + 9 * 60 * 60 * 1000);
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  const appointment = await prisma.appointment.create({ data: { jobId: job.id, technicianId: technician.id,
    serviceDate: new Date(`${date}T00:00:00Z`), windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: end, sequence: 0 } });
  try {
    const url = `/api/technicians/${technician.id}/standard-availability`;
    expect((await request.put(url, { data: { days: [] } })).status()).toBe(400);
    expect((await request.put(`/api/technicians/${missingWeek.id}/standard-availability`,
      { data: { days: initialAvailability(480, 1020).create.days.create } })).status()).toBe(409);
    const invalidPin = await request.post("/api/technicians", { data: { name: "Invalid pin", email: "pin@example.invalid", phone: "4025550100",
      bio: null, color: "#2563eb", depotId: depot.id, address: { line1: "1 Main St", city: "Omaha", state: "NE", postalCode: "68102" },
      confirmedPin: { lat: 0, lng: 0 }, manuallyConfirmed: false,
      days: initialAvailability(480, 1020).create.days.create, qualifications: [service.id] } });
    expect(invalidPin.status()).toBe(422);
    const days = initialAvailability(480, 1020).create.days.create.map(day => day.dayOfWeek === 1
      ? { ...day, available: false, shiftStartMin: null, shiftEndMin: null } : day);
    const response = await request.put(url, { data: { days } });
    expect(response.status()).toBe(409);
    await prisma.appointment.delete({ where: { id: appointment.id } });
    await prisma.slotHold.create({ data: { id: `profile-hold-${technician.id}`, offerToken: `profile-offer-${technician.id}`,
      jobId: job.id, technicianId: technician.id, serviceDate: new Date(`${date}T00:00:00Z`),
      windowStart: start, windowEnd: end, plannedStart: start, plannedEnd: end, insertPosition: 0,
      expiresAt: new Date(Date.now() + 60 * 60 * 1000) } });
    expect((await request.put(url, { data: { days } })).status()).toBe(409);
    expect(await prisma.technicianAvailabilityVersion.count({ where: { technicianId: technician.id } })).toBe(1);
  } finally {
    await prisma.slotHold.deleteMany({ where: { jobId: job.id } });
    await prisma.appointment.deleteMany({ where: { id: appointment.id } });
    await prisma.job.delete({ where: { id: job.id } });
    await prisma.address.delete({ where: { id: address.id } });
    await prisma.customer.delete({ where: { id: customer.id } });
    await prisma.serviceCatalog.delete({ where: { id: service.id } });
    await prisma.technician.delete({ where: { id: missingWeek.id } });
    await prisma.technician.delete({ where: { id: technician.id } });
    await prisma.depot.delete({ where: { id: depot.id } });
    await prisma.dealership.delete({ where: { id: dealership.id } });
    await prisma.metro.delete({ where: { id: metro.id } });
  }
});
