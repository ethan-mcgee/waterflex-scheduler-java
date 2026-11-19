import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import BookingWizard from "./BookingWizard";

export const dynamic = "force-dynamic";

export default async function NewWorkOrderPage() {
  const client = await activeClient();
  const [services, accounts] = await Promise.all([
    prisma.serviceCatalog.findMany({
      where: { active: true },
      orderBy: [{ sortOrder: "asc" }, { name: "asc" }],
      select: { code: true, name: true, estDurationMin: true, bufferPct: true },
    }),
    prisma.account.count({ where: { clientId: client.id } }),
  ]);

  return <BookingWizard services={services} accountsAvailable={accounts > 0} />;
}
