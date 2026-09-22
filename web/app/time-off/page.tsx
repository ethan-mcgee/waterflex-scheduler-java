import { prisma } from "@/lib/prisma";
import TimeOffDemo from "./TimeOffDemo";

export const dynamic = "force-dynamic";

export default async function TimeOffPage() {
  const technicians = await prisma.technician.findMany({ where: { active: true }, orderBy: { name: "asc" }, select: { id: true, name: true } });
  const requests = await prisma.timeOffRequest.findMany({
    include: { technician: { select: { name: true } }, intervals: { orderBy: { serviceDate: "asc" } }, report: true },
    orderBy: { createdAt: "desc" }, take: 100,
  });
  return <TimeOffDemo technicians={technicians} requests={requests.map((request) => ({
    id: request.id, technicianId: request.technicianId, technicianName: request.technician.name,
    category: request.category, reason: request.reason, status: request.status, createdAt: request.createdAt.toISOString(),
    intervals: request.intervals.map((interval) => ({ date: interval.serviceDate.toISOString().slice(0, 10), startMin: interval.startMin, endMin: interval.endMin })),
    reportStatus: request.report?.status ?? null, reportProgress: request.report?.progress ?? null,
    report: request.report?.data ?? null,
  }))} />;
}
