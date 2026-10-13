import styles from "@/app/dispatch/dispatch.module.css";
import type { ApiProposal } from "@/app/dispatch/ApiProposalReview";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import { clock, clockRange } from "@/app/dispatch/format";
import { changeKind, type ChangeKind } from "@/lib/planDiff";

const KIND_CLASS: Record<ChangeKind, string | undefined> = { Reassigned: styles.kindReassigned, Resequenced: styles.kindResequenced, Retimed: styles.kindRetimed };

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]?.toUpperCase() ?? "").join("");
}

/** Every visit the optimized plan changes. Selecting a technician highlights their route; selecting again shows all. */
export default function ChangesTable({ proposal, appointments, technicians, timezone, focusTechnicianId, onFocus }: {
  proposal: ApiProposal;
  appointments: BoardAppointment[];
  technicians: BoardTechnician[];
  timezone: string;
  focusTechnicianId: string | null;
  onFocus: (technicianId: string | null) => void;
}) {
  const byId = new Map(appointments.map(item => [item.id, item]));
  const techById = new Map(technicians.map(item => [item.id, item]));
  const days = proposal.routes.length + proposal.skippedTechnicianDays.length;
  return <section aria-labelledby="changes-heading" className={styles.card}>
    <div className={styles.cardHeader}>
      <div>
        <h2 id="changes-heading">What changes</h2>
        <p className={styles.caption}>{proposal.changes.length} visit{proposal.changes.length === 1 ? "" : "s"}. Select a technician to highlight their route on the map; select again to show all.</p>
      </div>
      <p className={styles.captionNarrow}>Committing saves new versions of {days} technician day{days === 1 ? "" : "s"}. If someone edits one of them first, the commit is refused and you can re-run.</p>
    </div>
    <div className={styles.tableScroll}>
      <table className={styles.changes}>
        <thead><tr><th scope="col">Technician</th><th scope="col">Visit</th><th scope="col">Window</th><th scope="col">Before</th><th scope="col">After</th><th scope="col">Change</th></tr></thead>
        <tbody>
          {proposal.changes.map(change => {
            const appointment = byId.get(change.appointmentId);
            const tech = techById.get(change.toTechnicianId);
            const from = techById.get(change.fromTechnicianId);
            const kind = changeKind(change);
            const name = tech?.name ?? change.toTechnicianId;
            const focused = focusTechnicianId === change.toTechnicianId;
            return <tr key={change.appointmentId}>
              <td>
                <button type="button" className={styles.techButton} aria-pressed={focused} onClick={() => onFocus(focused ? null : change.toTechnicianId)}>
                  <span className={styles.techRing} style={{ borderColor: tech?.color ?? "#69766f" }} aria-hidden="true">{initials(name)}</span>
                  <span className={styles.techName}>{name}</span>
                </button>
              </td>
              <td><div className={styles.stack}><strong>{appointment?.customerName ?? change.appointmentId}</strong>{appointment && <span className={styles.caption}>{appointment.serviceName}</span>}</div></td>
              <td className={styles.nowrap}>{appointment ? clockRange(appointment.windowStart, appointment.windowEnd, timezone) : "Unknown"}</td>
              <td className={styles.nowrap}>
                <div className={styles.stack}><span>{clock(change.fromStart, timezone)} · stop {change.fromSequence + 1}</span>
                  {kind === "Reassigned" && <span className={styles.caption}>{from?.name ?? change.fromTechnicianId}</span>}</div>
              </td>
              <td className={`${styles.nowrap} ${styles.strong}`}>{clock(change.toStart, timezone)} · stop {change.toSequence + 1}</td>
              <td><span className={KIND_CLASS[kind]}>{kind}</span></td>
            </tr>;
          })}
        </tbody>
      </table>
    </div>
  </section>;
}
