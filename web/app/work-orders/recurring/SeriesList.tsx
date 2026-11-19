import type { DemandBranch, DemandSeries } from "@/lib/recurringDemand";
import { Panel } from "@/app/portal/ui";
import { customerOf, dayLabel } from "./labels";
import styles from "./recurring.module.css";

const every = (series: DemandSeries) => `Every ${series.intervalCount} ${series.intervalUnit === "WEEK" ? "week" : "month"}${series.intervalCount === 1 ? "" : "s"}`;

function status(series: DemandSeries): string {
  if (series.reservation?.status === "BOOKED") return "Booked";
  if (series.reservation?.status === "RESERVED") return "Reserved";
  return series.overdue ? "Overdue" : "Not booked";
}

/** Every active series, next due first, with its branch and where its due date stands. */
export default function SeriesList({ series, branches }: { series: readonly DemandSeries[]; branches: readonly DemandBranch[] }) {
  if (series.length === 0) return <p className={styles.note} role="status">No recurring series match. Import them with scripts/import-recurring.ts.</p>;
  return <Panel className={styles.listPanel} label="Recurring series">
    <table className={styles.list}>
      <thead><tr><th>Customer</th><th>Address</th><th>Reason codes</th><th>Repeats</th><th>Last visit</th><th>Next due</th><th>Branch</th><th>Status</th></tr></thead>
      <tbody>{series.map(item => <tr key={item.id} data-series-row={item.id}>
        <td><strong>{customerOf(item)}</strong><small>Account {item.accountNumber}</small></td>
        <td>{item.addressLine}<small>{item.place}</small></td>
        <td>{item.codes.map(code => code.name).join(", ")}{item.workOrderType !== null && <small>{item.workOrderType}</small>}</td>
        <td>{every(item)}</td>
        <td>{item.lastVisitDate === null ? "Not recorded" : dayLabel(item.lastVisitDate)}</td>
        <td>{dayLabel(item.dueDate)}</td>
        <td>{item.branchId === null ? item.location === null ? "None: no WaterFlex coordinates" : "None: outside every service area"
          : branches.find(branch => branch.id === item.branchId)?.name ?? "Unknown branch"}</td>
        <td>{status(item)}</td>
      </tr>)}</tbody>
    </table>
  </Panel>;
}
