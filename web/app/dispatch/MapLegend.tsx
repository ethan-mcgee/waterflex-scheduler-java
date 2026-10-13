import styles from "@/app/dispatch/dispatch.module.css";

/** The map's legend: each entry only when the map shows it. */
export default function MapLegend({ currentRoute, homes, depot, changed }: { currentRoute: boolean; homes: boolean; depot: boolean; changed: boolean }) {
  if (!currentRoute && !homes && !depot && !changed) return null;
  return <ul className={styles.legend} aria-label="Map legend">
    {currentRoute && <li><svg aria-hidden="true" width="22" height="6"><line x1="0" y1="3" x2="22" y2="3" stroke="#69766f" strokeWidth="2" strokeDasharray="4 3" /></svg>Current route</li>}
    {homes && <li><span className={styles.legendHome}><svg aria-hidden="true" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 10.5 12 3l9 7.5V21H3z" /></svg></span>Technician home</li>}
    {depot && <li><span className={styles.legendDepot}><svg aria-hidden="true" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M22 8.35V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.35A2 2 0 0 1 3.26 6.5l8-3.2a2 2 0 0 1 1.48 0l8 3.2A2 2 0 0 1 22 8.35Z" /><rect x="6" y="10" width="12" height="12" /></svg></span>Depot</li>}
    {changed && <li><span className={styles.legendChanged} />Changed stop</li>}
  </ul>;
}
