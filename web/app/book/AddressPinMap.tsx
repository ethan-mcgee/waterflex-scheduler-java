"use client";

import { useEffect, useRef } from "react";
import maplibregl from "maplibre-gl";
import { localMapStyle } from "@/lib/localMapStyle";

export interface PinCandidate { lat: number; lng: number; precision: string }

export default function AddressPinMap({ candidates, selected, onSelect }: {
  candidates: PinCandidate[];
  selected: number;
  onSelect: (index: number) => void;
}) {
  const element = useRef<HTMLDivElement>(null);
  const selectedRef = useRef(selected);
  const selectRef = useRef(onSelect);
  selectedRef.current = selected;
  selectRef.current = onSelect;
  useEffect(() => {
    if (!element.current || !candidates[0]) return;
    const map = new maplibregl.Map({
      container: element.current, style: localMapStyle,
      center: [candidates[0].lng, candidates[0].lat], zoom: 15,
    });
    map.addControl(new maplibregl.NavigationControl());
    const markers = candidates.map((candidate, index) => new maplibregl.Marker({ color: index === selectedRef.current ? "#1558a6" : "#d8752d" })
      .setLngLat([candidate.lng, candidate.lat])
      .setPopup(new maplibregl.Popup().setText(`Candidate ${index + 1}`))
      .addTo(map));
    markers.forEach((marker, index) => marker.getElement().addEventListener("click", () => selectRef.current(index)));
    return () => { markers.forEach((marker) => marker.remove()); map.remove(); };
  }, [candidates]);
  return <div ref={element} style={{ width: "100%", height: 340, borderRadius: 8 }} />;
}
