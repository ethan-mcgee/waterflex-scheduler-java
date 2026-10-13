// The portal's client boundaries are enforced by its database, not only by its routes: a row's clientId never changes,
// and a technician, depot or dealership never joins another client's rows, even when two clients share a metro.
// Every fixture row is rolled back.
//   DATABASE_URL=<portal test database> npx tsx scripts/check-client-boundaries.ts
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Prisma, PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();
class RolledBack extends Error {}

/** Numbers the `?` placeholders as Postgres expects. */
function numbered(sql: string): string {
  let index = 0;
  return sql.replace(/\?/g, () => `$${++index}`);
}

async function main() {
  try {
    await prisma.$transaction(async tx => {
      const run = (sql: string, ...args: string[]) => tx.$executeRawUnsafe(numbered(sql), ...args);
      /** Runs a statement that must fail with the named rule, inside a savepoint so the fixture transaction survives. */
      async function rejects(rule: string, sql: string, ...args: string[]) {
        await tx.$executeRawUnsafe("SAVEPOINT client_rule");
        const error = await run(sql, ...args).then(() => null, (failure: unknown) => failure);
        await tx.$executeRawUnsafe("ROLLBACK TO SAVEPOINT client_rule");
        assert.ok(error !== null, `Expected "${rule}" for: ${sql}`);
        const message = error instanceof Prisma.PrismaClientKnownRequestError || error instanceof Error ? error.message : String(error);
        assert.ok(message.includes(rule), message);
      }

      const prefix = `clients-check-${randomUUID()}`;
      const acme = `${prefix}-acme`, brook = `${prefix}-brook`;
      for (const client of [acme, brook]) {
        await run("INSERT INTO client (id,name) VALUES (?,?)", client, client);
        await run("INSERT INTO dealership (id,\"clientId\",name,\"updatedAt\") VALUES (?,?,?,CURRENT_TIMESTAMP)", client, client, client);
        await run("INSERT INTO metro (id,name,timezone) VALUES (?,?,'America/Chicago')", client, client);
        await run("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.25,-95.93)", client, client, client, client);
        await run("INSERT INTO technician (id,\"clientId\",name,color,\"homeLat\",\"homeLng\",\"shiftStartMin\",\"shiftEndMin\",\"updatedAt\") VALUES (?,?,?,'#000000',41.25,-95.93,480,1020,CURRENT_TIMESTAMP)", client, client, client);
        await run("INSERT INTO customer (id,\"clientId\",\"firstName\",\"lastName\",email,phone) VALUES (?,?,'Client','Fixture','client@example.invalid','0000000000')", client, client);
      }
      await run("INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'1900-01-01')", acme, acme);

      await rejects("clientId cannot change", "UPDATE dealership SET \"clientId\"=? WHERE id=?", brook, acme);
      await rejects("clientId cannot change", "UPDATE technician SET \"clientId\"=? WHERE id=?", brook, acme);
      await rejects("clientId cannot change", "UPDATE customer SET \"clientId\"=? WHERE id=?", brook, acme);
      await rejects("depot of its own client", "INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2000-01-01')", acme, brook);
      await rejects("depot of its own client", "UPDATE technician_depot_assignment SET \"depotId\"=? WHERE \"technicianId\"=?", brook, acme);
      await rejects("another client's dealership", "UPDATE depot SET \"dealershipId\"=? WHERE id=?", brook, acme);
      // Two clients may serve the same metro, each with its own depots and technicians.
      await run("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.25,-95.93)", `${brook}-second`, acme, brook, brook);
      assert.equal(await run("UPDATE depot SET \"metroId\"=? WHERE id=?", acme, brook), 1);
      await rejects("depot of its own client", "INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2001-01-01')", acme, `${brook}-second`);
      await rejects("another client's dealership", "UPDATE depot SET \"dealershipId\"=? WHERE id=?", acme, `${brook}-second`);

      // The same client may add depots to its own metro, and an unchanged clientId is not a change.
      await run("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.26,-95.94)", `${acme}-second`, acme, acme, acme);
      await run("INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2000-01-01')", acme, `${acme}-second`);
      assert.equal(await run("UPDATE technician SET \"clientId\"=?, name='Renamed' WHERE id=?", acme, acme), 1);
      throw new RolledBack();
    }, { timeout: 60_000 });
  } catch (error) {
    if (!(error instanceof RolledBack)) throw error;
  } finally {
    await prisma.$disconnect();
  }
  console.log("Client boundaries hold in the portal database");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
