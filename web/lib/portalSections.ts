/**
 * The portal's sections: each is one icon on the frame's rail and one menu beside it. A page belongs to the section
 * whose menu link matches its path most closely, so /technicians/time-off is the Time off page, not the Roster.
 */

export type SectionKey = "work-orders" | "dispatch" | "technicians" | "branches" | "settings" | "testing";

export interface PortalLink { href: string; label: string }

export interface PortalSection {
  key: SectionKey;
  label: string;
  links: readonly PortalLink[];
  /** Shown only where booking tests are enabled (see bookingTestsEnabled). */
  testingOnly?: boolean;
}

export const PORTAL_SECTIONS: readonly PortalSection[] = [
  { key: "work-orders", label: "Work orders", links: [
    { href: "/work-orders/schedule", label: "Schedule" },
    { href: "/work-orders/recurring", label: "Recurring" },
    { href: "/work-orders/outstanding", label: "Outstanding" },
    { href: "/work-orders/statistics", label: "Statistics" },
    { href: "/work-orders/archive", label: "Archive" },
    { href: "/work-orders/follow-up", label: "Follow-up" },
  ] },
  { key: "dispatch", label: "Dispatch", links: [{ href: "/dispatch", label: "Optimize routes" }] },
  { key: "technicians", label: "Technicians", links: [{ href: "/technicians", label: "Roster" }, { href: "/technicians/time-off", label: "Time off" }] },
  { key: "branches", label: "Branches", links: [{ href: "/branches", label: "Dealerships and depots" }] },
  { key: "settings", label: "Settings", links: [{ href: "/settings/solver", label: "Solver settings" },
    { href: "/settings/reason-codes", label: "Reason codes" }, { href: "/settings/accounts", label: "Accounts" }] },
  { key: "testing", label: "Booking test", links: [{ href: "/dispatch/testing", label: "Sequential booking test" }], testingOnly: true },
];

/** The sections this portal shows: the booking test section only where booking tests are enabled. */
export function visibleSections(showBookingTests: boolean): PortalSection[] {
  return PORTAL_SECTIONS.filter(section => showBookingTests || section.testingOnly !== true);
}

function matches(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * The section and menu link a path belongs to: the link whose address matches the most of the path. A path no link
 * matches (the home page, an unknown address) belongs to work orders with no link marked.
 */
export function locate(pathname: string | null, sections: readonly PortalSection[]): { section: PortalSection; link: PortalLink | null } {
  let best: { section: PortalSection; link: PortalLink } | null = null;
  for (const section of sections) for (const link of section.links)
    if (pathname !== null && matches(pathname, link.href) && (best === null || link.href.length > best.link.href.length)) best = { section, link };
  if (best !== null) return best;
  const fallback = sections.find(section => section.key === "work-orders");
  if (fallback === undefined) throw new Error("The portal has no work orders section");
  return { section: fallback, link: null };
}

/** Where a section's rail icon leads: its first menu link. */
export function sectionHome(section: PortalSection): string {
  const first = section.links[0];
  if (first === undefined) throw new Error(`The ${section.label} section has no pages`);
  return first.href;
}
