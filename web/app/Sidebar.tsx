"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  CalendarDays,
  CalendarOff,
  ClipboardPlus,
  Droplets,
  FlaskConical,
  PhoneCall,
  Route,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
};

const NAV_ITEMS: NavItem[] = [
  { href: "/book", label: "Book Service Visit", icon: ClipboardPlus },
  { href: "/schedule", label: "Weekly Schedule", icon: CalendarDays },
  { href: "/dispatch", label: "Dispatch Board", icon: Route },
  { href: "/technicians", label: "Technicians", icon: Users },
  { href: "/dealerships", label: "Dealerships", icon: Users },
  { href: "/time-off", label: "Time Off", icon: CalendarOff },
  { href: "/dispatch/follow-up", label: "Manual Follow-up", icon: PhoneCall },
];

const TESTING_ITEM: NavItem = {
  href: "/dispatch/testing",
  label: "Sequential Booking Test",
  icon: FlaskConical,
};

export default function Sidebar({ showBookingTests }: { showBookingTests: boolean }) {
  const pathname = usePathname();
  const items = showBookingTests ? [...NAV_ITEMS, TESTING_ITEM] : NAV_ITEMS;

  return (
    <aside className="app-sidebar">
      <Link className="sidebar-brand" href="/schedule">
        <span className="sidebar-brand-mark">
          <Droplets size={20} />
        </span>
        <span className="sidebar-brand-text">
          <strong>WaterFlex</strong>
          <small>Scheduler</small>
        </span>
      </Link>
      <nav className="sidebar-nav" aria-label="Scheduler sections">
        {items.map(({ href, label, icon: Icon }) => {
          const active = pathname === href;
          return (
            <Link key={href} href={href} className={active ? "active" : undefined}>
              <Icon size={17} />
              {label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
