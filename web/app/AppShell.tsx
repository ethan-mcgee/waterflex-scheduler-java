import type { ReactNode } from "react";
import { bookingTestsEnabled } from "@/lib/bookingTestAccess";
import { activeClientAndList } from "@/lib/activeClient";
import Sidebar from "./Sidebar";
import ShellFrame from "./ShellFrame";

export default async function AppShell({ children }: { children: ReactNode }) {
  const { active, clients } = await activeClientAndList();
  return (
    <ShellFrame sidebar={<Sidebar showBookingTests={bookingTestsEnabled()} clients={clients} activeClientId={active.id} />}>
      {children}
    </ShellFrame>
  );
}
