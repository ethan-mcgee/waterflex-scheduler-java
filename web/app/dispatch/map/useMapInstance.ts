"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import maplibregl from "maplibre-gl";
import { localMapStyle } from "@/lib/localMapStyle";

/**
 * One MapLibre map for the life of its container. The center is read once, when the map is made; later views move the
 * camera themselves, so a re-render never rebuilds the map or refetches its tiles.
 */
export function useMapInstance(container: RefObject<HTMLDivElement | null>, center: [number, number]): { map: maplibregl.Map | null; loaded: boolean } {
  const initialCenter = useRef(center);
  const [map, setMap] = useState<maplibregl.Map | null>(null);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    const element = container.current;
    if (element === null) return;
    const created = new maplibregl.Map({ container: element, style: localMapStyle, center: initialCenter.current, zoom: 10 });
    created.addControl(new maplibregl.NavigationControl(), "top-right");
    created.on("load", () => setLoaded(true));
    setMap(created);
    return () => {
      setLoaded(false);
      setMap(null);
      created.remove();
    };
  }, [container]);
  return { map, loaded };
}
