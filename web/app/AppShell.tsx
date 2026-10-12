import type { ReactNode } from "react";
import { bookingTestsEnabled } from "@/lib/bookingTestAccess";
import { activeClientAndList } from "@/lib/activeClient";
import Sidebar from "./Sidebar";

export default async function AppShell({ children }: { children: ReactNode }) {
  const { active, clients } = await activeClientAndList();
  return (
    <div className="app-shell">
      <Sidebar showBookingTests={bookingTestsEnabled()} clients={clients} activeClientId={active.id} />
      <main className="app-main">{children}</main>
    </div>
  );
}
