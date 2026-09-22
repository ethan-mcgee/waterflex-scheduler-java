import type { ReactNode } from "react";
import { bookingTestsEnabled } from "@/lib/bookingTestAccess";
import Sidebar from "./Sidebar";

export default function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="app-shell">
      <Sidebar showBookingTests={bookingTestsEnabled()} />
      <main className="app-main">{children}</main>
    </div>
  );
}
