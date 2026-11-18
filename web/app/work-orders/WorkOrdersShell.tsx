"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import {
  Bell, Building2, Calculator, ChevronRight, CircleDollarSign, CircleHelp, ClipboardList, Droplets, MessageSquareText, Search, Truck,
  UserRound, Users, Wrench,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ClientSummary } from "@/lib/clients";
import DealershipMenu from "./DealershipMenu";
import theme from "./theme.module.css";
import styles from "./shell.module.css";

/** The section's pages, in the order of the developers' design. */
export const WORK_ORDER_SECTIONS: ReadonlyArray<{ slug: string; label: string }> = [
  { slug: "schedule", label: "Schedule" },
  { slug: "recurring", label: "Recurring" },
  { slug: "outstanding", label: "Outstanding" },
  { slug: "statistics", label: "Statistics" },
  { slug: "archive", label: "Archive" },
];

/** The rail's other products. They are not part of this portal, so they are shown but lead nowhere. */
const RAIL_ITEMS: ReadonlyArray<{ label: string; icon: LucideIcon }> = [
  { label: "Customers", icon: Users },
  { label: "Deliveries", icon: Truck },
  { label: "Billing", icon: CircleDollarSign },
  { label: "Reports", icon: ClipboardList },
  { label: "Branches", icon: Building2 },
  { label: "Pricing", icon: Calculator },
];

function InertRailIcon({ label, icon: Icon }: { label: string; icon: LucideIcon }) {
  return <span className={styles.railItem} role="img" aria-label={`${label} (not in this portal)`} title={`${label} is not part of this portal`}>
    <Icon size={22} aria-hidden />
  </span>;
}

export default function WorkOrdersShell({ clients, activeClientId, children }: {
  clients: ClientSummary[]; activeClientId: string; children: ReactNode;
}) {
  const pathname = usePathname();
  const [customers, deliveries, ...rest] = RAIL_ITEMS;
  return (
    <div className={`${theme.theme} ${styles.frame}`} data-work-orders-shell>
      <nav className={styles.rail} aria-label="Products">
        <Link className={styles.logo} href="/work-orders/schedule" aria-label="Work orders home">
          <Droplets size={30} aria-hidden />
        </Link>
        <div className={styles.railItems}>
          {customers && <InertRailIcon {...customers} />}
          {deliveries && <InertRailIcon {...deliveries} />}
          <Link className={`${styles.railItem} ${styles.railActive}`} href="/work-orders/schedule" aria-label="Work orders" aria-current="page">
            <Wrench size={22} aria-hidden />
          </Link>
          {rest.map(item => <InertRailIcon key={item.label} {...item} />)}
        </div>
        <div className={styles.railFooter}>
          <InertRailIcon label="Help" icon={CircleHelp} />
          <InertRailIcon label="Messages" icon={MessageSquareText} />
          <InertRailIcon label="Expand menu" icon={ChevronRight} />
        </div>
      </nav>

      <header className={styles.topbar}>
        <span className={styles.product}>Work orders</span>
        <label className={styles.search}>
          <span className="sr-only">Search work orders</span>
          <input type="search" placeholder="Search work orders by date, type or assigned technician" readOnly aria-disabled="true"
            title="Search is not available yet" />
          <Search size={20} aria-hidden />
        </label>
        <div className={styles.topActions}>
          <DealershipMenu clients={clients} activeClientId={activeClientId} />
          <span className={styles.topIcon} role="img" aria-label="Notifications (not available yet)" title="Notifications are not available yet">
            <Bell size={20} aria-hidden />
          </span>
          <span className={styles.topIcon} role="img" aria-label="Account (not available yet)" title="Accounts are not available yet">
            <UserRound size={20} aria-hidden />
          </span>
        </div>
      </header>

      <nav className={styles.sections} aria-label="Work order sections">
        {WORK_ORDER_SECTIONS.map(({ slug, label }) => {
          const href = `/work-orders/${slug}`;
          const active = pathname === href || pathname.startsWith(`${href}/`);
          return <Link key={slug} href={href} className={active ? styles.sectionActive : undefined} aria-current={active ? "page" : undefined}>{label}</Link>;
        })}
      </nav>

      <main className={styles.content}>{children}</main>
    </div>
  );
}
