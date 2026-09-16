import { prisma } from "@/lib/prisma";
import BookingWizard from "@/app/book/BookingWizard";

export const dynamic = "force-dynamic";

export default async function BookPage() {
  const services = await prisma.serviceCatalog.findMany({
    where: { active: true },
    orderBy: { sortOrder: "asc" },
    select: { code: true, name: true, description: true, estDurationMin: true },
  });

  return <BookingWizard services={services} />;
}
