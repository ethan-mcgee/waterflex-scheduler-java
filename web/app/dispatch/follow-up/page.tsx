import { prisma } from "@/lib/prisma";

export const dynamic = "force-dynamic";

export default async function FollowUpPage() {
  const jobs = await prisma.job.findMany({
    where: { manualFollowUpStatus: "PENDING" },
    include: { customer: true, address: true, service: true },
    orderBy: { createdAt: "asc" },
  });
  return <main style={{ padding: "2rem", fontFamily: "system-ui", maxWidth: 1000 }}>
    <a href="/dispatch">Back to dispatch</a>
    <h1>Manual follow-up</h1>
    {jobs.length === 0 ? <p>No requests are waiting.</p> : <table cellPadding={10}>
      <thead><tr><th>Reference</th><th>Customer</th><th>Contact</th><th>Service</th><th>Address</th><th>Reason</th></tr></thead>
      <tbody>{jobs.map((job) => <tr key={job.id}>
        <td>{job.id}</td><td>{job.customer.firstName} {job.customer.lastName}</td>
        <td>{job.customer.phone}<br />{job.customer.email}</td><td>{job.service.name}</td>
        <td>{job.address.line1}, {job.address.city}, {job.address.state} {job.address.postalCode}</td>
        <td>{job.manualFollowUpReason}</td>
      </tr>)}</tbody>
    </table>}
  </main>;
}
