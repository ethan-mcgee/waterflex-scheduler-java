import { MapPinOff } from "lucide-react";
import { activeClient } from "@/lib/activeClient";
import { loadDemand } from "@/lib/recurringDemand";
import { EmptyState, PageHeader } from "@/app/portal/ui";
import RecurringDemand from "./RecurringDemand";

export const dynamic = "force-dynamic";

export default async function RecurringWorkOrdersPage({ searchParams: query }: { searchParams: Promise<{ tab?: string }> }) {
  const searchParams = await query;
  const data = await loadDemand((await activeClient()).id);
  const header = <PageHeader crumbs={["Work Orders", "Recurring"]} title="Recurring work orders"
    description="Customers' recurring services by branch and week, against the free time technicians have. Reserved times are planning marks: the scheduler sees a visit once its work order is placed on the calendar." />;
  if (data === null) return <>
    {header}
    <EmptyState icon={MapPinOff} title="No branch is set up yet">Add a depot under Branches to see recurring demand here.</EmptyState>
  </>;
  return <>
    {header}
    <RecurringDemand data={data} tab={searchParams.tab === "list" ? "list" : "demand"} />
  </>;
}
