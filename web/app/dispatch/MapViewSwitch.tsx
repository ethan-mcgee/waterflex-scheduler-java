import styles from "@/app/dispatch/dispatch.module.css";
import type { MapView } from "@/app/dispatch/DispatchMap";

const VIEWS: Array<{ id: MapView; label: string }> = [{ id: "current", label: "Current" }, { id: "proposal", label: "Optimized" }, { id: "compare", label: "Compare" }];

/** Which plan the map shows; it floats on the map's top-left corner. */
export default function MapViewSwitch({ view, onChange }: { view: MapView; onChange: (view: MapView) => void }) {
  return <div role="group" aria-label="Plan shown on map" className={styles.viewSwitch}>
    {VIEWS.map(item => <button key={item.id} type="button" aria-pressed={view === item.id} onClick={() => onChange(item.id)}>{item.label}</button>)}
  </div>;
}
