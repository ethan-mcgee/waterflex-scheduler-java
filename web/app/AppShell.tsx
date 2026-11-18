import type { ReactNode } from "react";
import { bookingTestsEnabled } from "@/lib/bookingTestAccess";
import { activeClientAndList } from "@/lib/activeClient";
import PortalShell from "./portal/PortalShell";

/** Every page's frame: the portal shell, given the active client and whether booking tests are shown. */
export default async function AppShell({ children }: { children: ReactNode }) {
  const { active, clients } = await activeClientAndList();
  return <PortalShell clients={clients} activeClientId={active.id} showBookingTests={bookingTestsEnabled()}>{children}</PortalShell>;
}
