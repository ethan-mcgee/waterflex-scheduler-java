import styles from "./ui.module.css";

const PALETTE = ["#0157b6", "#237957", "#a66308", "#8c40b8", "#ad3c42", "#01336a"];

function colorFor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(hash) % PALETTE.length] ?? "#0157b6";
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]?.[0] ?? "" : "")).toUpperCase();
}

export default function Avatar({ name, size = 34, color }: { name: string; size?: number; color?: string }) {
  return (
    <span
      className={styles.avatar}
      style={{ width: size, height: size, fontSize: size * 0.36, background: color ?? colorFor(name) }}
    >
      {initials(name)}
    </span>
  );
}
