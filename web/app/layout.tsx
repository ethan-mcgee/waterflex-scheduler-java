import type { ReactNode } from "react";

export const metadata = {
  title: "WaterFlex Scheduler",
  description: "Book a WaterFlex technician visit",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
import "maplibre-gl/dist/maplibre-gl.css";
