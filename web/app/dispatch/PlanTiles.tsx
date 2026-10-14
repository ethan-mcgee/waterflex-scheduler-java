import styles from "@/app/dispatch/dispatch.module.css";
import { formatDelta, formatDollars, formatDuration, formatMiles } from "@/lib/planDiff";

/** One number on a tile: known, still loading, or unavailable with the reason. Never a stand-in zero. */
export type Metric = { state: "known"; value: number } | { state: "loading" } | { state: "unavailable"; reason: string };
export type PlanMetrics = { costCents: Metric; seconds: Metric; meters: Metric };

const TILES: Array<{ key: keyof PlanMetrics; label: string; format: (value: number) => string }> = [
  { key: "costCents", label: "Estimated cost", format: formatDollars },
  { key: "seconds", label: "Drive time", format: formatDuration },
  { key: "meters", label: "Distance", format: formatMiles },
];

function Value({ metric, format }: { metric: Metric; format: (value: number) => string }) {
  if (metric.state === "known") return <span className={styles.tileValue}>{format(metric.value)}</span>;
  if (metric.state === "loading") return <span className={styles.tilePending}>Loading</span>;
  return <span className={styles.tilePending} title={metric.reason}>Unavailable</span>;
}

/** Cost, drive time and distance of the current plan, or of the current and optimized plans side by side. */
export default function PlanTiles({ current, optimized }: { current: PlanMetrics; optimized: PlanMetrics | null }) {
  return <div className={styles.tiles}>
    {TILES.map(({ key, label, format }) => {
      const before = current[key], after = optimized?.[key];
      const delta = before.state === "known" && after?.state === "known" ? after.value - before.value : null;
      return <div className={styles.tile} key={key} role="group" aria-label={label}>
        <span className={styles.eyebrow}>{label}</span>
        <div className={styles.tileRow}>
          {after === undefined ? <>
            <Value metric={before} format={format} />
            <span className={styles.tileCaption}>Current plan</span>
          </> : <>
            {before.state === "known" && <span className={styles.tileBefore}><span className={styles.visuallyHidden}>Current plan </span>{format(before.value)}</span>}
            <Value metric={after} format={format} />
            {delta !== null && <span className={delta < 0 ? styles.deltaGood : styles.deltaNeutral}>{formatDelta(key, delta)}</span>}
          </>}
        </div>
      </div>;
    })}
  </div>;
}
