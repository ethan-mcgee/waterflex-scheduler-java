import SectionPlaceholder from "../SectionPlaceholder";
import { Button } from "@/app/portal/ui";

export default function WorkOrderArchivePage() {
  const actions = <>
    <Button variant="text" disabled title="Not available yet">Export</Button>
    <Button variant="text" disabled title="Not available yet">Print</Button>
    <Button variant="text" disabled title="Not available yet">Email PDF</Button>
  </>;
  return <SectionPlaceholder crumb="Archive" title="Work Orders Archive" actions={actions}
    filters={["Technician", "Work order type", "Date from", "Date to"]}>
    The archive needs completed work orders with their result codes, which the portal does not keep yet.
  </SectionPlaceholder>;
}
