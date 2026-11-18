/**
 * The portal's building blocks, drawn after the developers' Material-like design: pill buttons, outlined
 * fields with floating labels, a segmented view toggle, chips, type tags and status icons.
 */

import Link from "next/link";
import type { ReactNode } from "react";
import { CircleCheck, CircleX, Clock3, ListFilter, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import styles from "./ui.module.css";

type ButtonVariant = "filled" | "outlined" | "text";

export function Button({ variant = "filled", href, onClick, disabled, children, type = "button", title }: {
  variant?: ButtonVariant; href?: string; onClick?: () => void; disabled?: boolean; children: ReactNode; type?: "button" | "submit"; title?: string;
}) {
  const className = `${styles.button} ${styles[variant]}`;
  if (href !== undefined && !disabled) return <Link className={className} href={href} title={title}>{children}</Link>;
  return <button type={type} className={className} onClick={onClick} disabled={disabled} title={title}>{children}</button>;
}

export interface ToggleItem { key: string; label: string; icon: LucideIcon; href: string }

/** The Calendar, Map View and Dual View switch: links, so every view has its own address. */
export function SegmentedToggle({ items, selected, label }: { items: readonly ToggleItem[]; selected: string; label: string }) {
  return (
    <nav className={styles.toggle} aria-label={label}>
      {items.map(({ key, label: text, icon: Icon, href }) => <Link key={key} href={href} className={key === selected ? styles.toggleSelected : undefined}
        aria-current={key === selected ? "page" : undefined}><Icon size={17} aria-hidden />{text}</Link>)}
    </nav>
  );
}

/** A removable chip, as in the "Display calendar for" picker. */
export function Chip({ children, onRemove, removeLabel }: { children: ReactNode; onRemove?: () => void; removeLabel?: string }) {
  return (
    <span className={styles.chip}>
      {children}
      {onRemove && <button type="button" onClick={onRemove} aria-label={removeLabel}><CircleX size={15} aria-hidden /></button>}
    </span>
  );
}

/** A work order type pill in the type's pastel. */
export function TypeTag({ label, background }: { label: string; background: string }) {
  return <span className={styles.typeTag} style={{ background }}>{label}</span>;
}

/**
 * The work order statuses the portal knows today. The design also has Dispatched and Outstanding; they need data the
 * portal does not keep yet, so they are not drawn.
 */
export type WorkOrderStatus = "SCHEDULED" | "COMPLETED";

export const STATUS_LABEL: Readonly<Record<WorkOrderStatus, string>> = { SCHEDULED: "Scheduled", COMPLETED: "Completed" };

export function StatusIcon({ status, size = 16 }: { status: WorkOrderStatus; size?: number }) {
  const label = STATUS_LABEL[status];
  if (status === "COMPLETED") return <span className={`${styles.status} ${styles.statusCompleted}`} role="img" aria-label={label} title={label}>
    <CircleCheck size={size} aria-hidden />
  </span>;
  return <span className={`${styles.status} ${styles.statusScheduled}`} role="img" aria-label={label} title={label}>
    <Clock3 size={size} aria-hidden />
  </span>;
}

/** Breadcrumb, title, an optional one-line description and actions at the top of every page. */
export function PageHeader({ crumbs, title, description, actions }: {
  crumbs: readonly string[]; title: string; description?: ReactNode; actions?: ReactNode;
}) {
  return (
    <div className={styles.pageHeader}>
      <div>
        <nav className={styles.crumbs} aria-label="Breadcrumb">
          <ol>{crumbs.map((crumb, index) => <li key={`${index}-${crumb}`} aria-current={index === crumbs.length - 1 ? "page" : undefined}>{crumb}</li>)}</ol>
        </nav>
        <h1 className={styles.title}>{title}</h1>
        {description !== undefined && <p className={styles.description}>{description}</p>}
      </div>
      {actions && <div className={styles.headerActions}>{actions}</div>}
    </div>
  );
}

/** The row of filters under the page header, with Clear filters at the end. */
export function FilterBar({ children, onClear, canClear }: { children: ReactNode; onClear?: () => void; canClear: boolean }) {
  return (
    <div className={styles.filterBar} role="group" aria-label="Filters">
      <span className={styles.filterLead}><ListFilter size={18} aria-hidden />Filters</span>
      {children}
      <button type="button" className={styles.clearFilters} onClick={onClear} disabled={!canClear}>Clear filters</button>
    </div>
  );
}

/** A white rounded panel on the canvas. */
export function Panel({ children, className, label }: { children: ReactNode; className?: string; label?: string }) {
  return <section className={`${styles.panel} ${className ?? ""}`} aria-label={label}>{children}</section>;
}

export function EmptyState({ icon: Icon, title, children, actions }: { icon: LucideIcon; title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className={styles.empty} role="status">
      <Icon size={32} aria-hidden />
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {actions && <div className={styles.emptyActions}>{actions}</div>}
    </div>
  );
}

/** A small close button for popovers and drawers. */
export function CloseButton({ onClick, label }: { onClick: () => void; label: string }) {
  return <button type="button" className={styles.close} onClick={onClick} aria-label={label}><X size={20} aria-hidden /></button>;
}
