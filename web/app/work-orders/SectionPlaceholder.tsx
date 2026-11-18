import type { ReactNode } from "react";
import { Construction } from "lucide-react";
import { EmptyState, FilterBar, PageHeader } from "./ui";
import { SelectField } from "./ui/fields";

const NOT_YET = "Not available yet";

/**
 * A section of the design the portal does not have yet: its header and filters in the design's look, disabled, and
 * a note saying so. It shows no sample rows, so nothing on it can be mistaken for real work orders.
 */
export default function SectionPlaceholder({ crumb, title, filters = [], actions, children }: {
  crumb: string; title: string; filters?: readonly string[]; actions?: ReactNode; children: ReactNode;
}) {
  return <>
    <PageHeader crumbs={["Work Orders", crumb]} title={title} actions={actions} />
    {filters.length > 0 && <FilterBar canClear={false}>
      {filters.map(label => <SelectField key={label} label={label} value="" options={[]} disabled hint={NOT_YET} />)}
    </FilterBar>}
    <EmptyState icon={Construction} title={`${crumb} is not built yet`}>{children}</EmptyState>
  </>;
}
