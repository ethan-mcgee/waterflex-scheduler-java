"use client";

import { useEffect, useState } from "react";
import styles from "@/app/dispatch/dispatch.module.css";

export type RunStep = "optimizing" | "checking" | "mapping";
const STEPS: Array<{ id: "loading" | RunStep; label: string }> = [
  { id: "loading", label: "Loading" }, { id: "optimizing", label: "Optimizing" }, { id: "checking", label: "Checking" }, { id: "mapping", label: "Mapping" },
];
/** The bar holds here until the scheduler answers, since the search can end before or after its configured time. */
const HOLD = 95;

/**
 * The optimize run's steps and progress bar. The bar fills over the configured search time and holds at 95% until the
 * answer arrives; with no configured time it is indeterminate rather than a made-up length.
 */
export default function ProgressSteps({ step, startedAt, searchSeconds }: { step: RunStep; startedAt: number; searchSeconds: number | null }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (step !== "optimizing" || searchSeconds === null) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [step, searchSeconds]);
  const active = STEPS.findIndex(item => item.id === step);
  const percent = step !== "optimizing" ? 100 : searchSeconds === null ? null
    : Math.min(HOLD, Math.max(0, ((now - startedAt) / 1000 / searchSeconds) * HOLD));
  return <>
    <ol className={styles.steps}>
      {STEPS.map((item, index) => {
        const state = index < active ? "done" : index === active ? "active" : "pending";
        return <li key={item.id} className={styles[`step_${state}`]}>
          <span aria-hidden="true" className={styles.stepDot}>{state === "done" ? "✓" : index + 1}</span>
          <span>{item.label}<span className={styles.visuallyHidden}>{state === "done" ? ", done" : state === "active" ? ", in progress" : ", waiting"}</span></span>
        </li>;
      })}
    </ol>
    {percent === null
      ? <div role="progressbar" aria-label="Optimization progress" className={styles.progress}><div className={styles.progressIndeterminate} /></div>
      : <div role="progressbar" aria-label="Optimization progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(percent)} className={styles.progress}>
        <div className={styles.progressFill} style={{ width: `${percent}%` }} />
      </div>}
  </>;
}
