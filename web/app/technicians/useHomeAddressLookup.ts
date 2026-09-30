"use client";

import { useEffect, useRef, useState } from "react";
import { z } from "zod";
import { errorMessage, readResponse } from "@/lib/contracts";
import type { GeocodeResult } from "@/lib/geocode";

const candidatesResponse = z.object({ candidates: z.array(z.object({ lat: z.number().finite(), lng: z.number().finite(),
  precision: z.enum(["ROOFTOP", "APPROXIMATE"]), bounds: z.object({ south: z.number(), north: z.number(), west: z.number(), east: z.number() }).optional() })) });
const LOOKUP_DEBOUNCE_MS = 700;

export interface HomeAddress { line1: string; city: string; state: string; postalCode: string }
export interface HomeAddressPayload { address: HomeAddress; confirmedPin: { lat: number; lng: number }; manuallyConfirmed: boolean }
const EMPTY_ADDRESS: HomeAddress = { line1: "", city: "", state: "", postalCode: "" };

// Address lookup and pin confirmation shared by Add technician and Edit profile. With an initial (saved)
// address the form starts clean: no lookup and nothing is sent until a field changes.
export function useHomeAddressLookup(initial?: HomeAddress | null) {
  const [address, setAddress] = useState<HomeAddress>(initial ?? EMPTY_ADDRESS);
  const [dirty, setDirty] = useState(initial === undefined);
  const [selectedCandidate, setSelectedCandidate] = useState<GeocodeResult | null>(null);
  const [pin, setPin] = useState<{ lat: number; lng: number } | null>(null);
  const [pinConfirmed, setPinConfirmed] = useState(false);
  const [pinMoved, setPinMoved] = useState(false);
  const [mapAvailable, setMapAvailable] = useState(false);
  const [lookup, setLookup] = useState<"idle" | "loading" | "done" | "error">("idle");
  const [lookupError, setLookupError] = useState("");
  const lookupVersion = useRef(0);

  const fieldValues = [address.line1, address.city, address.state, address.postalCode];
  const addressComplete = fieldValues.every(value => value.trim().length > 0);
  const addressEmpty = fieldValues.every(value => value.trim().length === 0);
  const hadSavedAddress = initial != null;
  const pinReady = Boolean(pin && selectedCandidate && (selectedCandidate.precision === "ROOFTOP" && !pinMoved || pinConfirmed && mapAvailable));
  // Nothing to send when untouched, or when a technician without a saved address leaves the fields blank.
  const nothingToSave = !dirty || (addressEmpty && !hadSavedAddress);
  const ready = nothingToSave || pinReady;
  const payload: HomeAddressPayload | null = nothingToSave || !pinReady || !pin ? null : {
    address: { line1: address.line1.trim(), city: address.city.trim(), state: address.state.trim(), postalCode: address.postalCode.trim() },
    confirmedPin: pin, manuallyConfirmed: (selectedCandidate?.precision === "APPROXIMATE" || pinMoved) && pinConfirmed,
  };

  useEffect(() => {
    const version = ++lookupVersion.current;
    if (!dirty || !addressComplete) { setLookup("idle"); return; }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      setLookup("loading"); setLookupError("");
      fetch("/api/technicians/geocode", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(address), signal: controller.signal })
        .then(response => readResponse(response, candidatesResponse))
        .then(result => {
          if (controller.signal.aborted || version !== lookupVersion.current) return;
          const best = result.candidates.find(candidate => candidate.precision === "ROOFTOP") ?? result.candidates[0] ?? null;
          if (!best) { setSelectedCandidate(null); setPin(null); setLookup("error"); return; }
          setSelectedCandidate(best); setPin({ lat: best.lat, lng: best.lng }); setPinConfirmed(false); setPinMoved(false); setMapAvailable(false); setLookup("done");
        })
        .catch(error => { if (!controller.signal.aborted && version === lookupVersion.current) { setSelectedCandidate(null); setPin(null); setLookup("error"); setLookupError(errorMessage(error)); } });
    }, LOOKUP_DEBOUNCE_MS);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [address, addressComplete, dirty]);

  function updateAddress(field: keyof HomeAddress, value: string) {
    ++lookupVersion.current;
    setDirty(true);
    setAddress(current => ({ ...current, [field]: value }));
    setSelectedCandidate(null); setPin(null); setPinConfirmed(false); setPinMoved(false); setMapAvailable(false); setLookup("idle"); setLookupError("");
  }
  function movePin(lat: number, lng: number) { setPin({ lat, lng }); setPinMoved(true); setPinConfirmed(false); }

  return { address, dirty, updateAddress, selectedCandidate, pin, movePin, pinConfirmed, confirmPin: () => setPinConfirmed(true),
    pinMoved, mapAvailable, setMapAvailable, lookup, lookupError, pinReady, ready, payload };
}

export type HomeAddressState = ReturnType<typeof useHomeAddressLookup>;
