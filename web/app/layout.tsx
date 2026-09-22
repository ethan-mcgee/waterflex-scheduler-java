import type { ReactNode } from "react";
import AppShell from "./AppShell";
import "./globals.css";
import "maplibre-gl/dist/maplibre-gl.css";

export const metadata = {
  title: "WaterFlex Scheduler",
  description: "Book a WaterFlex technician visit",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
