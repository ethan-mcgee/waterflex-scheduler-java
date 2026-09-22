import styles from "./ui.module.css";

const VARIANTS: Record<string, { className: string; label: string }> = {
  PENDING: { className: styles.pillWarning ?? "", label: "Pending" },
  ANALYZING: { className: styles.pillWarning ?? "", label: "Analyzing" },
  NEEDS_COORDINATION: { className: styles.pillWarning ?? "", label: "Needs coordination" },
  READY: { className: styles.pillBrand ?? "", label: "Ready for review" },
  APPROVED: { className: styles.pillSuccess ?? "", label: "Approved" },
};

export default function StatusPill({ status }: { status: string }) {
  const variant = VARIANTS[status] ?? { className: styles.pillNeutral ?? "", label: status };
  return <span className={`${styles.pill} ${variant.className}`}>{variant.label}</span>;
}
