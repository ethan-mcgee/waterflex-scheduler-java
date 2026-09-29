import { z } from "zod";
import { locationBounds, serviceAreaCircle } from "./contracts";

export function coverageBounds(raw: unknown, marginMi = 0) {
  const circles = z.array(serviceAreaCircle).min(1).parse(raw);
  if (!Number.isFinite(marginMi) || marginMi < 0) throw new Error("Invalid coverage margin");
  const boxes = circles.map(circle => {
    const angular = (circle.radiusMi + marginMi) / 3958.8;
    const latitude = circle.lat * Math.PI / 180;
    const deltaLat = angular * 180 / Math.PI;
    const deltaLng = Math.asin(Math.sin(angular) / Math.cos(latitude)) * 180 / Math.PI;
    return { south: circle.lat - deltaLat, north: circle.lat + deltaLat, west: circle.lng - deltaLng, east: circle.lng + deltaLng };
  });
  return locationBounds.parse({ south: Math.min(...boxes.map(b => b.south)), north: Math.max(...boxes.map(b => b.north)),
    west: Math.min(...boxes.map(b => b.west)), east: Math.max(...boxes.map(b => b.east)) });
}
