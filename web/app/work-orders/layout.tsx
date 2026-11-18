import type { ReactNode } from "react";
import type { Metadata } from "next";
import { activeClientAndList } from "@/lib/activeClient";
import WorkOrdersShell from "./WorkOrdersShell";

export const metadata: Metadata = { title: "Work orders | WaterFlex Scheduler" };

/** The work orders section's own frame, in the WaterFlex developers' layout; the classic shell steps aside for it. */
export default async function WorkOrdersLayout({ children }: { children: ReactNode }) {
  const { active, clients } = await activeClientAndList();
  return <WorkOrdersShell clients={clients} activeClientId={active.id}>{children}</WorkOrdersShell>;
}
