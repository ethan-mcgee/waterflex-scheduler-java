// Client solver settings against a migrated database: no row means not configured, saves never overwrite a newer save,
// and the database itself rejects out-of-range values. Uses a throwaway client and removes it afterwards.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Prisma } from "@prisma/client";
import { prisma } from "../lib/prisma";
import { publicPolicy, publicRates } from "../lib/clientSettings";
import { loadSolverSettings, storeSolverSettings } from "../lib/clientSettingsStore";

const clientId = `settings-it-${randomUUID()}`;
const input = { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67", travelBufferPercent: "20",
  travelBufferMinutes: 5, arrivalBufferPercent: 25, arrivalBufferMinMinutes: 15, arrivalBufferMaxMinutes: 60, fairnessBudgetPercent: "2", offerLimit: 4, windowHours: 4, bookingHorizonWeekdays: 10, horizonMode: "FIXED" as const, horizonLeadWeekdays: 2, horizonFullPercent: 85, dailySearch: "TABU_KOPT" as const, costSearchSeconds: 10, fairnessSearchSeconds: 5, unimprovedSearchSeconds: null, bookingDeadlineMs: 5000, regularWindowThreshold: 2 };

async function main() {
  await prisma.client.create({ data: { id: clientId, name: "Settings smoke" } });
  try {
    assert.equal(await loadSolverSettings(clientId), null, "A new client has no settings until they are saved");

    const created = await storeSolverSettings(clientId, null, input);
    assert.ok("saved" in created);
    assert.equal(created.saved.version, 0);
    assert.deepEqual(publicRates(created.saved), { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
      travelBufferPct: "0.2", travelBufferMinutes: 5, arrivalBuffer: { percent: 25, minMinutes: 15, maxMinutes: 60 } });
    assert.deepEqual(publicPolicy(created.saved), { fairnessBudget: "0.02", dailySearch: "TABU_KOPT", costSearchSeconds: 10, fairnessSearchSeconds: 5, bookingDeadlineMs: 5000, regularWindowThreshold: 2 });
    assert.deepEqual(await storeSolverSettings(clientId, null, input), { stale: true }, "A second first save is stale");

    const updated = await storeSolverSettings(clientId, 0, { ...input, offerLimit: 2 });
    assert.ok("saved" in updated);
    assert.equal(updated.saved.version, 1);
    assert.equal(updated.saved.offerLimit, 2);
    assert.deepEqual(await storeSolverSettings(clientId, 0, { ...input, offerLimit: 3 }), { stale: true }, "An edit of an old version is stale");
    assert.equal((await loadSolverSettings(clientId))?.offerLimit, 2, "A stale save changes nothing");

    const racing = await Promise.all([1, 3].map(offerLimit => storeSolverSettings(clientId, 1, { ...input, offerLimit })));
    assert.equal(racing.filter(outcome => "saved" in outcome).length, 1, "Exactly one of two saves of the same version wins");
    assert.equal((await loadSolverSettings(clientId))?.version, 2);

    for (const [column, value] of [["offerLimit", 5], ["windowHours", 0], ["windowHours", 9], ["bookingHorizonWeekdays", 16], ["travelBufferMinutes", 121],
      ["travelBufferPct", 1.5], ["fairnessBudget", 1.01], ["regularHourly", -1], ["dailySearch", "KOPT"], ["dailySearch", "tabu_kopt"],
      ["costSearchSeconds", 4], ["costSearchSeconds", 241], ["fairnessSearchSeconds", -1], ["fairnessSearchSeconds", 241],
      ["bookingDeadlineMs", 999], ["bookingDeadlineMs", 5001], ["regularWindowThreshold", -1], ["regularWindowThreshold", 1001],
      ["horizonMode", "ROLLING"], ["horizonMode", "dynamic"], ["horizonLeadWeekdays", 0], ["horizonLeadWeekdays", 6],
      ["horizonFullPercent", 49], ["horizonFullPercent", 101], ["unimprovedSearchSeconds", 0], ["unimprovedSearchSeconds", 241],
      ["arrivalBufferPercent", 51], ["arrivalBufferPercent", -1], ["arrivalBufferMinMinutes", 61], ["arrivalBufferMaxMinutes", 241]] as const) {
      await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET ${Prisma.raw(`"${column}"`)} = ${value} WHERE "clientId" = ${clientId}`,
        `The database rejects ${column} = ${value}`);
    }
    await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET "costSearchSeconds" = 200, "fairnessSearchSeconds" = 41 WHERE "clientId" = ${clientId}`,
      "The database rejects search times over 240 seconds together");
    await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET "costSearchSeconds" = 10, "unimprovedSearchSeconds" = 11 WHERE "clientId" = ${clientId}`,
      "The database rejects an unimproved limit longer than the cost search");
    await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET "dailySearch" = NULL WHERE "clientId" = ${clientId}`,
      "The database rejects a missing search method");
    await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET "bookingDeadlineMs" = NULL WHERE "clientId" = ${clientId}`,
      "The database rejects a missing booking deadline");
    for (const column of ["horizonMode", "horizonLeadWeekdays", "horizonFullPercent"])
      await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET ${Prisma.raw(`"${column}"`)} = NULL WHERE "clientId" = ${clientId}`,
        `The database rejects a missing ${column}`);
    const limited = await storeSolverSettings(clientId, 2, { ...input, dailySearch: "TABU_KOPT_SIZE_3", unimprovedSearchSeconds: 6 });
    assert.ok("saved" in limited && limited.saved.dailySearch === "TABU_KOPT_SIZE_3" && limited.saved.unimprovedSearchSeconds === 6,
      "TABU_KOPT_SIZE_3 and an unimproved limit save");
    assert.equal(publicPolicy(limited.saved).unimprovedSearchSeconds, 6);
    const dynamic = await storeSolverSettings(clientId, 3, { ...input, horizonMode: "DYNAMIC", bookingHorizonWeekdays: 15, horizonLeadWeekdays: 3, horizonFullPercent: 90 });
    assert.ok("saved" in dynamic && dynamic.saved.horizonMode === "DYNAMIC" && dynamic.saved.horizonLeadWeekdays === 3 && dynamic.saved.horizonFullPercent === 90,
      "A dynamic horizon saves its cap, lead and full share");
    await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET "regularWindowThreshold" = NULL WHERE "clientId" = ${clientId}`,
      "The database rejects a missing window threshold");
    const omahaSetting = await prisma.$queryRaw<Array<{ present: boolean }>>`SELECT to_regclass('public.omaha_setting') IS NOT NULL AS present`;
    assert.deepEqual(omahaSetting, [{ present: false }], "The shared policy table is gone; every rule is the client's own");
    console.log("Client solver settings smoke passed");
  } finally {
    await prisma.clientSolverSettings.deleteMany({ where: { clientId } });
    await prisma.client.delete({ where: { id: clientId } });
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
