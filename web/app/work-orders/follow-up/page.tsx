import { Inbox } from "lucide-react";
import { prisma } from "@/lib/prisma";
import { activeClient } from "@/lib/activeClient";
import { Button, EmptyState, PageHeader, Panel } from "@/app/portal/ui";
import FollowUpActions from "./FollowUpActions";
import styles from "./follow-up.module.css";

export const dynamic = "force-dynamic";

/** Names for the stored codes. A code the portal does not know is shown as stored rather than guessed at. */
const STATUS: Readonly<Record<string, string>> = { PENDING: "Waiting", CONTACTED: "Contacted" };
const REASON: Readonly<Record<string, string>> = { ADDRESS_UNVERIFIED: "Address not verified" };

export default async function FollowUpPage() {
  const clientId = (await activeClient()).id;
  const jobs = await prisma.job.findMany({
    where: { customer: { clientId }, manualFollowUpStatus: { in: ["PENDING", "CONTACTED"] } },
    include: { customer: true, address: true, service: true },
    orderBy: { createdAt: "asc" },
  });
  return <>
    <PageHeader crumbs={["Work orders", "Follow-up"]} title="Manual follow-up"
      description="Requests that need a call before they can be booked: the customer has no promised window yet." />
    {jobs.length === 0 ? <EmptyState icon={Inbox} title="No requests are waiting" /> : <Panel className={styles.panel} label="Requests waiting for follow-up">
      <table className={styles.table}>
        <thead><tr><th>Customer</th><th>Contact</th><th>Service</th><th>Address</th><th>Reason</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{jobs.map(job => {
          const status = job.manualFollowUpStatus;
          const reason = job.manualFollowUpReason;
          // A located job still waiting for the calendar can be placed on the schedule directly.
          const placeable = job.status === "PENDING" && job.address.lat !== null && job.address.lng !== null;
          return <tr key={job.id} data-follow-up={job.id}>
            <td><strong>{job.customer.firstName} {job.customer.lastName}</strong><small title="Reference">{job.id}</small></td>
            <td>{job.customer.phone}<small>{job.customer.email}</small></td>
            <td>{job.service.name}</td>
            <td>{job.address.line1}<small>{job.address.city}, {job.address.state} {job.address.postalCode}</small></td>
            <td>{reason === null ? "Not recorded" : REASON[reason] ?? reason}</td>
            <td>{status === null ? "Not recorded" : <span className={styles.status} data-status={status}>{STATUS[status] ?? status}</span>}</td>
            <td><div className={styles.actions}>
              <FollowUpActions jobId={job.id} />
              {placeable && <Button variant="text" href={`/work-orders/schedule?placeJob=${encodeURIComponent(job.id)}`}>Place on calendar</Button>}
            </div></td>
          </tr>;
        })}</tbody>
      </table>
    </Panel>}
  </>;
}
