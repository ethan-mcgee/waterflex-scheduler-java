import SectionPlaceholder from "../SectionPlaceholder";

export default function RecurringWorkOrdersPage() {
  return <SectionPlaceholder crumb="Recurring" title="Recurring work orders"
    filters={["Work order type", "Technician", "Branch", "City / ZIP", "Outreach status"]}>
    Recurring series and customer outreach need data the portal does not keep yet.
  </SectionPlaceholder>;
}
