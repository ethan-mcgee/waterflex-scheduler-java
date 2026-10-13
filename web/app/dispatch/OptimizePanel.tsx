import type { ReactNode } from "react";
import styles from "@/app/dispatch/dispatch.module.css";
import type { ApiProposal } from "@/app/dispatch/ApiProposalReview";
import PlanTiles, { type PlanMetrics } from "@/app/dispatch/PlanTiles";
import ProgressSteps, { type RunStep } from "@/app/dispatch/ProgressSteps";

export type Phase =
  | { kind: "ready" }
  | { kind: "running"; step: RunStep; startedAt: number; proposal: ApiProposal | null }
  | { kind: "result"; proposal: ApiProposal };

const ROUTE_ICON = <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="6" cy="19" r="3" /><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15" /><circle cx="18" cy="5" r="3" /></svg>;
const CHECK_ICON = <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>;

/** The verdict at the top of the result card, from the scheduler's decision and what has happened to the proposal since. */
function verdict(proposal: ApiProposal): { pill: string; tone: string | undefined; headline: string } {
  if (proposal.state === "COMMITTED") return { pill: "Applied", tone: styles.pill_good, headline: "The optimized plan is live" };
  if (proposal.state === "REFUSED") return { pill: "Cannot be applied", tone: styles.pill_warn, headline: proposal.refusal ?? "This proposal can no longer be applied" };
  if (proposal.decision === "IMPROVED") return { pill: "Improved", tone: styles.pill_good, headline: "Optimized plan ready to review" };
  if (proposal.decision === "NO_IMPROVEMENT") return { pill: "No improvement", tone: styles.pill_neutral, headline: "Your current plan is already the best found" };
  return { pill: "Rejected by policy", tone: styles.pill_warn, headline: "The optimized plan was not accepted" };
}

type Props = {
  phase: Phase;
  timezone: string;
  current: PlanMetrics;
  optimized: PlanMetrics | null;
  searchSeconds: number | null;
  /** Why optimizing is blocked, one entry per problem; any entry disables the button. */
  blockers: ReactNode[];
  /** Days the scheduler will leave out; optimizing still runs. */
  warnings: string[];
  commitBlockedReason: string | null;
  committing: boolean;
  onOptimize: () => void;
  onDiscard: () => void;
  onCommit: () => void;
};

function Notices({ blockers, warnings }: { blockers: ReactNode[]; warnings: string[] }) {
  if (blockers.length === 0 && warnings.length === 0) return null;
  return <ul className={styles.notices}>
    {blockers.map((item, index) => <li key={`b${index}`} className={styles.noticeBlocked}>{item}</li>)}
    {warnings.map(item => <li key={item} className={styles.noticeWarning}>{item}</li>)}
  </ul>;
}

/** The optimize card: ready to optimize, optimizing, or the optimizer's result with commit and discard. */
export default function OptimizePanel({ phase, timezone, current, optimized, searchSeconds, blockers, warnings, commitBlockedReason, committing, onOptimize, onDiscard, onCommit }: Props) {
  if (phase.kind === "running") return <section aria-labelledby="optimize-heading" aria-live="polite" className={styles.card}>
    <h2 id="optimize-heading">Optimizing routes</h2>
    <ProgressSteps step={phase.step} startedAt={phase.startedAt} searchSeconds={searchSeconds} />
  </section>;

  if (phase.kind === "ready") return <section aria-labelledby="optimize-heading" className={styles.card}>
    <div className={styles.cardHeader}>
      <h2 id="optimize-heading">Ready to optimize</h2>
      <button type="button" className={styles.primaryButton} disabled={blockers.length > 0} onClick={onOptimize}>{ROUTE_ICON}Optimize routes</button>
    </div>
    <PlanTiles current={current} optimized={null} />
    <Notices blockers={blockers} warnings={warnings} />
  </section>;

  const { proposal } = phase;
  const { pill, tone, headline } = verdict(proposal);
  const committable = proposal.decision === "IMPROVED" && proposal.state === "OPEN";
  const made = new Intl.DateTimeFormat("en-US", { timeZone: timezone, hour: "numeric", minute: "2-digit" }).format(new Date(proposal.createdAt));
  return <section aria-labelledby="optimize-heading" className={styles.card}>
    <div className={styles.cardHeader}>
      <div className={styles.stack}>
        <div className={styles.verdict}><span className={tone}>{pill}</span><h2 id="optimize-heading">{headline}</h2></div>
        <span className={styles.caption}>{proposal.overnightRunId !== null ? "Overnight proposal" : "Proposal"} made {made}. {proposal.reason}</span>
      </div>
      <div className={styles.actions}>
        <button type="button" className={styles.secondaryButton} onClick={onOptimize} disabled={committing || blockers.length > 0}>Re-run</button>
        <button type="button" className={styles.secondaryButton} onClick={onDiscard} disabled={committing}>{proposal.state === "COMMITTED" ? "Done" : "Discard"}</button>
        {committable && <button type="button" className={styles.primaryButton} onClick={onCommit} disabled={committing || commitBlockedReason !== null}
          title={commitBlockedReason ?? undefined}>{CHECK_ICON}{committing ? "Committing..." : "Commit optimized plan"}</button>}
      </div>
    </div>
    {commitBlockedReason !== null && committable && <p role="alert" className={styles.banner}>{commitBlockedReason}</p>}
    <PlanTiles current={current} optimized={optimized} />
    <Notices blockers={blockers} warnings={warnings} />
  </section>;
}
