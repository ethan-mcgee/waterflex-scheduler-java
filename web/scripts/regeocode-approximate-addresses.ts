import { geocodeAddress } from "../lib/geocode";
import { prisma } from "../lib/prisma";

const apply = process.argv.includes("--apply");

async function main() {
  const addresses = await prisma.address.findMany({
    where: { geocodePrecision: "APPROXIMATE" },
    orderBy: { createdAt: "asc" },
  });

  if (addresses.length === 0) {
    console.log("No approximate addresses need geocoding.");
    return;
  }

  let matched = 0;
  for (const address of addresses) {
    const result = await geocodeAddress(address);
    if (!result) {
      console.warn(`No match for address ${address.id}.`);
      continue;
    }

    matched += 1;
    if (apply) {
      await prisma.address.update({
        where: { id: address.id },
        data: {
          lat: result.lat,
          lng: result.lng,
          geocodePrecision: result.precision,
          geocodedAt: new Date(),
        },
      });
    }
    console.log(`${apply ? "Updated" : "Would update"} address ${address.id}.`);
  }

  console.log(`${apply ? "Updated" : "Matched"} ${matched} of ${addresses.length} address(es).`);
  if (!apply) console.log("Run again with --apply to save these coordinates.");
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
