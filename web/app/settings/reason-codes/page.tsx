import { prisma } from "@/lib/prisma";
import { PageHeader } from "@/app/portal/ui";
import ReasonCodesEditor from "./ReasonCodesEditor";

export const dynamic = "force-dynamic";

export default async function ReasonCodesPage() {
  const codes = await prisma.serviceCatalog.findMany({
    orderBy: [{ active: "desc" }, { name: "asc" }],
    select: { code: true, name: true, estDurationMin: true, bufferPct: true, active: true, _count: { select: { qualifications: true } } },
  });
  return <>
    <PageHeader crumbs={["Settings", "Reason codes"]} title="Reason codes"
      description="WaterFlex's complaint codes. Their minutes and buffer suggest a work order's time allotted, and a work order's first code decides which technicians are qualified. Every client in this portal shares them." />
    <ReasonCodesEditor codes={codes.map(code => ({ code: code.code, name: code.name, estDurationMin: code.estDurationMin, bufferPct: code.bufferPct,
      active: code.active, qualifiedTechnicians: code._count.qualifications }))} />
  </>;
}
