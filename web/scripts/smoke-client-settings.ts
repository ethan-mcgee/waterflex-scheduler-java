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
  travelBufferMinutes: 5, fairnessBudgetPercent: "2", offerLimit: 4, bookingHorizonWeekdays: 10 };

async function main() {
  await prisma.client.create({ data: { id: clientId, name: "Settings smoke" } });
  try {
    assert.equal(await loadSolverSettings(clientId), null, "A new client has no settings until they are saved");

    const created = await storeSolverSettings(clientId, null, input);
    assert.ok("saved" in created);
    assert.equal(created.saved.version, 0);
    assert.deepEqual(publicRates(created.saved), { regularHourly: "30", overtimeHourly: "45", mileagePerMile: "0.67",
      travelBufferPct: "0.2", travelBufferMinutes: 5 });
    assert.deepEqual(publicPolicy(created.saved), { fairnessBudget: "0.02" });
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

    for (const [column, value] of [["offerLimit", 5], ["bookingHorizonWeekdays", 16], ["travelBufferMinutes", 121],
      ["travelBufferPct", 1.5], ["fairnessBudget", 1.01], ["regularHourly", -1]] as const) {
      await assert.rejects(prisma.$executeRaw`UPDATE client_solver_settings SET ${Prisma.raw(`"${column}"`)} = ${value} WHERE "clientId" = ${clientId}`,
        `The database rejects ${column} = ${value}`);
    }
    console.log("Client solver settings smoke passed");
  } finally {
    await prisma.clientSolverSettings.deleteMany({ where: { clientId } });
    await prisma.client.delete({ where: { id: clientId } });
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => prisma.$disconnect());
