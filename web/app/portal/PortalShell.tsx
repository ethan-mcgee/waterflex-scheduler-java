"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Bell, Building2, Droplets, FlaskConical, Route, Search, SlidersHorizontal, UserRound, Users, Wrench } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ClientSummary } from "@/lib/clients";
import { locate, sectionHome, visibleSections, type SectionKey } from "@/lib/portalSections";
import ConnectionStatus from "./ConnectionStatus";
import DealershipMenu from "./DealershipMenu";
import theme from "./theme.module.css";
import styles from "./portal.module.css";

const ICONS: Readonly<Record<SectionKey, LucideIcon>> = {
  "work-orders": Wrench,
  dispatch: Route,
  technicians: Users,
  branches: Building2,
  settings: SlidersHorizontal,
  testing: FlaskConical,
};

/**
 * The portal's one frame, in the WaterFlex developers' layout: an icon rail of sections, a top bar with the dealership
 * menu and the scheduling API's status, the open section's menu, and the page.
 */
export default function PortalShell({ clients, activeClientId, showBookingTests, children }: {
  clients: ClientSummary[]; activeClientId: string; showBookingTests: boolean; children: ReactNode;
}) {
  const pathname = usePathname();
  const sections = visibleSections(showBookingTests);
  const { section: active, link: current } = locate(pathname, sections);
  return (
    <div className={`${theme.theme} ${styles.frame}`} data-portal-frame data-section={active.key}>
      <nav className={styles.rail} aria-label="Sections">
        <Link className={styles.logo} href="/work-orders/schedule" aria-label="WaterFlex home">
          <Droplets size={30} aria-hidden />
        </Link>
        <div className={styles.railItems}>
          {sections.map(section => {
            const Icon = ICONS[section.key];
            const selected = section.key === active.key;
            // The name comes from hidden text, not aria-label, so a section name never answers a page's label lookup.
            return <Link key={section.key} href={sectionHome(section)} className={`${styles.railItem} ${selected ? styles.railActive : ""}`}
              title={section.label} aria-current={selected ? "true" : undefined}>
              <Icon size={22} aria-hidden />
              <span className="sr-only">{section.label}</span>
            </Link>;
          })}
        </div>
      </nav>

      <header className={styles.topbar}>
        <span className={styles.product}>{active.label}</span>
        {active.key === "work-orders" && <label className={styles.search}>
          <span className="sr-only">Search work orders</span>
          <input type="search" placeholder="Search work orders by date, type or assigned technician" readOnly aria-disabled="true"
            title="Search is not available yet" />
          <Search size={20} aria-hidden />
        </label>}
        <div className={styles.topActions}>
          <ConnectionStatus activeClientId={activeClientId} />
          <DealershipMenu clients={clients} activeClientId={activeClientId} />
          <span className={styles.topIcon} role="img" aria-label="Notifications (not available yet)" title="Notifications are not available yet">
            <Bell size={20} aria-hidden />
          </span>
          <span className={styles.topIcon} role="img" aria-label="Account (not available yet)" title="Accounts are not available yet">
            <UserRound size={20} aria-hidden />
          </span>
        </div>
      </header>

      <nav className={styles.sections} aria-label="Pages" data-section-menu>
        {active.links.map(({ href, label }) => {
          const selected = current?.href === href;
          return <Link key={href} href={href} className={selected ? styles.sectionActive : undefined} aria-current={selected ? "page" : undefined}>{label}</Link>;
        })}
      </nav>

      <main className={styles.content}>{children}</main>
    </div>
  );
}
