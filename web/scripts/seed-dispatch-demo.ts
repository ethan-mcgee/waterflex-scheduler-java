import { tomorrowInTz } from "../lib/date";
import { generateFakeData } from "../lib/fakeData";
import { OMAHA_TIMEZONE } from "../lib/fakeDataCore";
import { prisma } from "../lib/prisma";

function argument(name: string): string | undefined {
  return process.argv.find((value) => value.startsWith(`--${name}=`))?.slice(name.length + 3);
}

function optionalNumber(name: string): number | undefined {
  const value = argument(name);
  return value === undefined ? undefined : Number(value);
}

async function main() {
  const startDate = argument("date") ?? tomorrowInTz(OMAHA_TIMEZONE);
  const endDate = argument("end-date") ?? startDate;
  const totalCalls = optionalNumber("total") ?? 11;
  const seed = optionalNumber("seed");
  // Without --client the Omaha metro's only client gets the calls; a shared metro must name one.
  const result = await generateFakeData({ startDate, endDate, totalCalls, seed }, argument("client"));

  console.log(JSON.stringify(result, null, 2));
  console.log(`Open http://localhost:3001/schedule?week=${result.startDate} to review the schedule.`);
  console.log(`Open http://localhost:3001/dispatch?date=${result.startDate} to run optimization manually.`);
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
