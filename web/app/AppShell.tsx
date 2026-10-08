import type { ReactNode } from "react";
import { bookingTestsEnabled } from "@/lib/bookingTestAccess";
import { activeClient, listClients } from "@/lib/activeClient";
import Sidebar from "./Sidebar";

export default async function AppShell({ children }: { children: ReactNode }) {
  const [clients, active] = await Promise.all([listClients(), activeClient()]);
  return (
    <div className="app-shell">
      <Sidebar showBookingTests={bookingTestsEnabled()} clients={clients} activeClientId={active.id} />
      <main className="app-main">{children}</main>
    </div>
  );
}
