/**
 * The color each service is drawn in on the schedule: the card's edge, its tint, its border, its text, and the lighter
 * shade its drive time takes. Colors go to services in the order of their codes, so a service keeps its color from
 * week to week and on every screen; past the palette's length the colors repeat.
 */

export interface ServiceColor { color: string; tint: string; border: string; text: string; drive: string }

const PALETTE: ReadonlyArray<Omit<ServiceColor, "drive">> = [
  { color: "#2563eb", tint: "#eff6ff", border: "#bfdbfe", text: "#172554" },
  { color: "#0d9488", tint: "#f0fdfa", border: "#99f6e4", text: "#134e4a" },
  { color: "#d97706", tint: "#fffbeb", border: "#fde68a", text: "#78350f" },
  { color: "#7c3aed", tint: "#f5f3ff", border: "#ddd6fe", text: "#2e1065" },
  { color: "#e11d48", tint: "#fff1f2", border: "#fecdd3", text: "#881337" },
  { color: "#4f46e5", tint: "#eef2ff", border: "#c7d2fe", text: "#1e1b4b" },
  { color: "#059669", tint: "#ecfdf5", border: "#a7f3d0", text: "#064e3b" },
  { color: "#ea580c", tint: "#fff7ed", border: "#fed7aa", text: "#7c2d12" },
  { color: "#0891b2", tint: "#ecfeff", border: "#a5f3fc", text: "#164e63" },
  { color: "#475569", tint: "#f8fafc", border: "#cbd5e1", text: "#0f172a" },
];

/** How strongly a drive band shows its service color over the card's tint. */
const DRIVE_OPACITY = 0.3;

function rgba(hex: string, alpha: number): string {
  const channel = (start: number) => Number.parseInt(hex.slice(start, start + 2), 16);
  return `rgba(${channel(1)}, ${channel(3)}, ${channel(5)}, ${alpha})`;
}

/** The color of every service code, assigned in code order. Duplicate codes are refused: each code names one service. */
export function serviceColors(codes: readonly string[]): Map<string, ServiceColor> {
  const sorted = [...codes].sort();
  if (new Set(sorted).size !== sorted.length) throw new Error("Service codes must be unique to be given colors");
  return new Map(sorted.map((code, index) => {
    const base = PALETTE[index % PALETTE.length];
    if (base === undefined) throw new Error("The service palette is empty");
    return [code, { ...base, drive: rgba(base.color, DRIVE_OPACITY) }];
  }));
}

/** The color of one service, which must be in the map: a missing service is a loading mistake, never a default color. */
export function colorOf(colors: ReadonlyMap<string, ServiceColor>, code: string): ServiceColor {
  const color = colors.get(code);
  if (color === undefined) throw new Error(`Service ${code} has no schedule color`);
  return color;
}
