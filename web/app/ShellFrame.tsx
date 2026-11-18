"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import { isStandaloneSection } from "@/lib/shellSections";

/**
 * The classic sidebar shell around every page, except sections that draw their own frame (see shellSections). For
 * classic pages the markup is the same as before this frame existed.
 */
export default function ShellFrame({ sidebar, children }: { sidebar: ReactNode; children: ReactNode }) {
  const pathname = usePathname();
  if (isStandaloneSection(pathname)) return <>{children}</>;
  return (
    <div className="app-shell">
      {sidebar}
      <main className="app-main">{children}</main>
    </div>
  );
}
