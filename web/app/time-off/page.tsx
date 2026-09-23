import { prisma } from "@/lib/prisma";
import { parseTimeOffReport, timeOffIntervalView } from "@/lib/timeOffView";
import TimeOffDemo from "./TimeOffDemo";

export const dynamic = "force-dynamic";
const FILTERS = {
  all: ["PENDING", "READY", "APPROVED"],
  pending: ["PENDING"],
  ready: ["READY"],
  approved: ["APPROVED"],
  denied: ["DENIED"],
} as const;
type FilterKey = keyof typeof FILTERS;
function isFilterKey(value: string | undefined): value is FilterKey { return value != null && Object.hasOwn(FILTERS, value); }

export default async function TimeOffPage({ searchParams }: { searchParams: { status?: string } }) {
  const selectedFilter: FilterKey = isFilterKey(searchParams.status) ? searchParams.status : "all";
  const statuses = FILTERS[selectedFilter];
  const [technicians, loadedRequests] = await Promise.all([
    prisma.technician.findMany({ where: { active: true }, orderBy: { name: "asc" }, select: { id: true, name: true } }),
    prisma.timeOffRequest.findMany({
      where: statuses ? { status: { in: [...statuses] } } : undefined,
      include: { technician: { select: { name: true } }, intervals: { orderBy: { serviceDate: "asc" } }, report: true },
      orderBy: { createdAt: "desc" }, take: 101,
    }),
  ]);
  const truncated = loadedRequests.length > 100;
  const requests = loadedRequests.slice(0, 100).map(request => {
    const rawIntervals = request.intervals.map(interval => ({ date: interval.serviceDate.toISOString().slice(0, 10), startMin: interval.startMin, endMin: interval.endMin }));
    const parsedIntervals = timeOffIntervalView.array().nonempty().safeParse(rawIntervals);
    const intervalsValid = parsedIntervals.success;
    return {
      id: request.id, technicianId: request.technicianId, technicianName: request.technician.name,
      category: request.category, reason: request.reason, status: request.status, createdAt: request.createdAt.toISOString(),
      intervals: parsedIntervals.success ? parsedIntervals.data : [], intervalsValid,
      reportStatus: request.report?.status ?? null, reportProgress: request.report?.progress ?? null,
      report: parseTimeOffReport(request.report?.data),
    };
  });
  return <TimeOffDemo technicians={technicians} requests={requests} selectedFilter={selectedFilter} truncated={truncated} />;
}
