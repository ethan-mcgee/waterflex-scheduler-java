"use client";

import { apiProposal, apiProposals, errorMessage, readResponse } from "@/lib/contracts";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import styles from "@/app/dispatch/dispatch.module.css";
import type { BoardAppointment, BoardTechnician } from "@/app/dispatch/types";
import type { ApiProposal } from "@/app/dispatch/ApiProposalReview";
import type { GeometryResult, MapView } from "@/app/dispatch/DispatchMap";
import type { GeometryResponse } from "@/lib/dispatchGeometry";
import OptimizePanel, { type Phase } from "@/app/dispatch/OptimizePanel";
import type { Metric, PlanMetrics } from "@/app/dispatch/PlanTiles";
import MapViewSwitch from "@/app/dispatch/MapViewSwitch";
import MapLegend from "@/app/dispatch/MapLegend";
import ChangesTable from "@/app/dispatch/ChangesTable";
import StopDetail, { type StopPlacement } from "@/app/dispatch/StopDetail";
import { longDate, shiftDate, shortDate } from "@/app/dispatch/format";
import { changedAppointmentIds, changedRouteTechnicianIds } from "@/lib/planDiff";

const DispatchMap = dynamic(() => import("@/app/dispatch/DispatchMap"), { ssr: false, loading: () => <div className={styles.mapLoading}>Loading map...</div> });

type BoardProps = { metroId: string; metroName: string; timezone: string; date: string; searchSeconds: number | null;
  technicians: BoardTechnician[]; appointments: BoardAppointment[] };

const LOADING: Metric = { state: "loading" };
const committable = (proposal: ApiProposal) => proposal.decision === "IMPROVED" && proposal.state === "OPEN";

/** Tile numbers from a plan's geometry: loading until it arrives, unavailable with the reason when it cannot be drawn or priced. */
function metricsOf(result: GeometryResult | null, knownCost?: number): PlanMetrics {
  const cost: Metric | null = knownCost === undefined ? null : { state: "known", value: knownCost };
  if (result === null) return { costCents: cost ?? LOADING, seconds: LOADING, meters: LOADING };
  if ("error" in result) {
    const unavailable: Metric = { state: "unavailable", reason: result.error };
    return { costCents: cost ?? unavailable, seconds: unavailable, meters: unavailable };
  }
  const { totals } = result.geometry;
  if (totals === undefined) {
    const unreported: Metric = { state: "unavailable", reason: "The server did not report route totals." };
    return { costCents: cost ?? unreported, seconds: unreported, meters: unreported };
  }
  return { seconds: { state: "known", value: totals.seconds }, meters: { state: "known", value: totals.meters },
    costCents: cost ?? (totals.costCents === null ? { state: "unavailable", reason: totals.costUnavailableReason ?? "The day could not be priced." } : { state: "known", value: totals.costCents }) };
}

/** Which anchors the shown plan's routes use, so the legend lists only what the map draws. */
function anchorsShown(geometry: GeometryResponse | null): { homes: boolean; depot: boolean } {
  if (geometry === null) return { homes: false, depot: false };
  const routed = new Set(geometry.stops.map(stop => stop.technicianId));
  const anchors = (geometry.endpoints ?? []).filter(endpoint => routed.has(endpoint.technicianId)).flatMap(endpoint => [endpoint.departure, endpoint.returnTo]);
  return { homes: anchors.includes("HOME"), depot: anchors.includes("DEPOT") };
}

function DateStepper({ metroId, date }: { metroId: string; date: string }) {
  const router = useRouter();
  const go = (next: string) => { if (next) router.push(`/dispatch?metroId=${encodeURIComponent(metroId)}&date=${next}`); };
  return <div className={styles.stepper}>
    <button type="button" aria-label="Previous day" onClick={() => go(shiftDate(date, -1))}>
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m15 18-6-6 6-6" /></svg>
    </button>
    <label className={styles.stepperDate}><span className={styles.visuallyHidden}>Dispatch date</span><span aria-hidden="true">{shortDate(date)}</span>
      <input type="date" value={date} onChange={event => go(event.target.value)} /></label>
    <button type="button" aria-label="Next day" onClick={() => go(shiftDate(date, 1))}>
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6" /></svg>
    </button>
  </div>;
}

/** The map-first dispatch board: the day's routes, and optimizing them through the public scheduling API. */
export default function DispatchBoard({ metroId, metroName, timezone, date, searchSeconds, technicians, appointments }: BoardProps) {
  const router = useRouter();
  const [phase, setPhase] = useState<Phase>({ kind: "ready" });
  const [error, setError] = useState<string | null>(null);
  const [currentGeometry, setCurrentGeometry] = useState<GeometryResult | null>(null);
  const [proposalGeometry, setProposalGeometry] = useState<GeometryResult | null>(null);
  const [view, setView] = useState<MapView>("current");
  const [focus, setFocus] = useState<string | null>(null);
  const [selected, setSelected] = useState<{ id: string; plan: "current" | "proposal" } | null>(null);
  const [committing, setCommitting] = useState(false);
  const [commitError, setCommitError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  const showResult = useCallback((proposal: ApiProposal) => {
    setProposalGeometry(null); setCommitError(null); setFocus(null); setSelected(null);
    setView(committable(proposal) ? "compare" : "current");
    // An improved proposal is mapped before its result shows; anything else has no optimized routes to draw.
    setPhase(committable(proposal) ? { kind: "running", step: "mapping", startedAt: Date.now(), proposal } : { kind: "result", proposal });
  }, []);

  useEffect(() => {
    const request = new AbortController();
    void fetch(`/api/dispatch/proposals?${new URLSearchParams({ metroId, date })}`, { cache: "no-store", signal: request.signal })
      .then(response => readResponse(response, apiProposals))
      .then(({ proposals }) => {
        // An overnight improvement waits here for a dispatcher; open the newest one that can still be applied.
        const waiting = proposals.find(item => item.overnightRunId !== null && item.state === "OPEN");
        if (waiting === undefined || request.signal.aborted) return;
        setPhase(current => current.kind === "ready" ? { kind: "result", proposal: waiting } : current);
        setView("compare");
      })
      .catch((cause: unknown) => { if (!request.signal.aborted) setError(errorMessage(cause)); });
    return () => request.abort();
  }, [metroId, date]);

  const proposal = phase.kind === "result" ? phase.proposal : phase.kind === "running" ? phase.proposal : null;
  const mappedProposalId = proposal !== null && committable(proposal) ? proposal.proposalId : null;
  const changes = useMemo(() => proposal?.changes ?? [], [proposal]);
  const changedStops = useMemo(() => changedAppointmentIds(changes), [changes]);
  const changedRoutes = useMemo(() => changedRouteTechnicianIds(changes), [changes]);
  const names = useMemo(() => new Map(technicians.map(tech => [tech.id, tech.name])), [technicians]);

  const onCurrentGeometry = useCallback((result: GeometryResult) => setCurrentGeometry(result), []);
  const onProposalGeometry = useCallback((result: GeometryResult) => {
    setProposalGeometry(result);
    setPhase(current => current.kind === "running" && current.step === "mapping" && current.proposal !== null ? { kind: "result", proposal: current.proposal } : current);
  }, []);
  const onSelectStop = useCallback((id: string, plan: "current" | "proposal") => setSelected({ id, plan }), []);

  async function optimize() {
    const startedAt = Date.now();
    setError(null); setCommitError(null); setProposalGeometry(null); setView("current"); setFocus(null); setSelected(null);
    setPhase({ kind: "running", step: "optimizing", startedAt, proposal: null });
    try {
      const response = await fetch("/api/dispatch/proposals", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ metroId, date }) });
      setPhase({ kind: "running", step: "checking", startedAt, proposal: null });
      showResult(await readResponse(response, apiProposal));
    } catch (cause) {
      setError(errorMessage(cause));
      setPhase({ kind: "ready" });
    }
  }

  async function commit() {
    if (proposal === null || !committable(proposal)) return;
    if (!window.confirm(`Commit the optimized plan? ${proposal.changes.length} visit(s) change.`)) return;
    setCommitting(true); setCommitError(null); setError(null);
    try {
      const response = await fetch("/api/dispatch/proposals/commit", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ proposalId: proposal.proposalId }) });
      const applied = await readResponse(response, apiProposal);
      setPhase({ kind: "result", proposal: applied });
      setView("current"); setFocus(null); setSelected(null); setProposalGeometry(null); setCurrentGeometry(null);
      setRevision(value => value + 1);
      router.refresh();
    } catch (cause) { setCommitError(errorMessage(cause)); }
    finally { setCommitting(false); }
  }

  function discard() {
    setPhase({ kind: "ready" }); setProposalGeometry(null); setView("current"); setFocus(null); setSelected(null); setCommitError(null);
  }

  const blockers: ReactNode[] = [];
  if (technicians.length === 0) blockers.push("No technicians are assigned to this metro on this day.");
  else if (appointments.length === 0) blockers.push("No visits are booked on this day.");
  for (const appointment of appointments)
    if (appointment.lat === null || appointment.lng === null)
      blockers.push(<><strong>{appointment.customerName}</strong>: <span>Location data missing. Routing is blocked.</span></>);
  const skipped = currentGeometry !== null && "geometry" in currentGeometry ? currentGeometry.geometry.skippedTechnicianDays ?? [] : [];
  const warnings = skipped.map(day => `${names.get(day.technicianId) ?? day.technicianId}'s day will be left out: ${day.message}`);

  const proposalDrawn = proposalGeometry !== null && "geometry" in proposalGeometry ? proposalGeometry.geometry : null;
  const switchable = phase.kind === "result" && committable(phase.proposal) && proposalDrawn !== null;
  const shownView: MapView = switchable ? view : "current";
  const currentDrawn = currentGeometry !== null && "geometry" in currentGeometry ? currentGeometry.geometry : null;
  const shownGeometry = shownView === "current" ? currentDrawn : proposalDrawn;
  const legend = anchorsShown(shownGeometry);

  const commitBlockedReason = commitError
    ?? (proposalGeometry !== null && "error" in proposalGeometry && proposalGeometry.status === 409 ? proposalGeometry.error : null)
    ?? (blockers.length > 0 ? "Resolve the problems listed below before committing." : null);
  const proposalNotice = proposalGeometry !== null && "error" in proposalGeometry && proposalGeometry.status !== 409
    ? `Optimized routes could not be drawn: ${proposalGeometry.error}` : null;

  const optimized: PlanMetrics | null = phase.kind === "result" && committable(phase.proposal) ? metricsOf(proposalGeometry, phase.proposal.costCents) : null;
  const current = metricsOf(currentGeometry);

  const placement = selected === null ? null : placementOf(selected.id, selected.plan, appointments, technicians, proposal);
  const selectedAppointment = selected === null ? undefined : appointments.find(item => item.id === selected.id);

  const title = shownView === "current" ? "Current plan" : shownView === "proposal" ? "Optimized plan" : "Current vs. optimized";
  const caption = shownView === "compare" ? "Gray dashed lines are current routes; colored lines are the optimized routes"
    : shownGeometry === null ? null
    : `${shownGeometry.stops.length} visit${shownGeometry.stops.length === 1 ? "" : "s"} on ${new Set(shownGeometry.stops.map(stop => stop.technicianId)).size} route${new Set(shownGeometry.stops.map(stop => stop.technicianId)).size === 1 ? "" : "s"}`;

  return <div className={styles.board}>
    <header className={styles.header}>
      <div className={styles.stack}>
        <h1>Dispatch Board</h1>
        <span className={styles.inkSoft}>{metroName} · {longDate(date)} · {timezone}</span>
      </div>
      <DateStepper metroId={metroId} date={date} />
    </header>
    {error && <p role="alert" className={styles.banner}>{error}</p>}
    <OptimizePanel phase={phase} timezone={timezone} current={current} optimized={optimized} searchSeconds={searchSeconds} blockers={blockers} warnings={warnings}
      commitBlockedReason={commitBlockedReason} committing={committing} onOptimize={() => void optimize()} onDiscard={discard} onCommit={() => void commit()} />
    <section aria-labelledby="map-heading" className={styles.card}>
      <div className={styles.cardHeader}>
        <div className={styles.titleRow}><h2 id="map-heading">{title}</h2>{caption && <span className={styles.caption}>{caption}</span>}</div>
        <MapLegend currentRoute={shownView === "compare"} homes={legend.homes} depot={legend.depot} changed={shownView !== "current" && changedStops.size > 0} />
      </div>
      {proposalNotice && <p role="status" className={styles.bannerQuiet}>{proposalNotice}</p>}
      <div className={styles.mapLayout}>
        <div className={styles.mapPane}>
          <DispatchMap technicians={technicians} appointments={appointments} timezone={timezone} metroId={metroId} date={date} revision={revision}
            proposalId={mappedProposalId} view={shownView} focusTechnicianId={focus} changedAppointmentIds={changedStops} changedTechnicianIds={changedRoutes}
            dimmed={phase.kind === "running"} notice={phase.kind === "running" ? "Showing the current plan while the optimizer searches" : null}
            overlay={switchable ? <MapViewSwitch view={view} onChange={next => { setView(next); setSelected(null); }} /> : null}
            onSelectStop={onSelectStop} onCurrentGeometry={onCurrentGeometry} onProposalGeometry={onProposalGeometry} />
        </div>
        {placement !== null && selectedAppointment !== undefined &&
          <StopDetail appointment={selectedAppointment} placement={placement} timezone={timezone} onClose={() => setSelected(null)} />}
      </div>
    </section>
    {phase.kind === "result" && committable(phase.proposal) && phase.proposal.changes.length > 0 &&
      <ChangesTable proposal={phase.proposal} appointments={appointments} technicians={technicians} timezone={timezone} focusTechnicianId={focus}
        onFocus={technicianId => { setFocus(technicianId); if (technicianId !== null && view === "current") setView("compare"); }} />}
  </div>;
}

/** A visit's place in the plan the map showed when it was selected; null when that plan does not route it. */
function placementOf(id: string, plan: "current" | "proposal", appointments: BoardAppointment[], technicians: BoardTechnician[], proposal: ApiProposal | null): StopPlacement | null {
  const byId = new Map(appointments.map(item => [item.id, item]));
  const techOf = (technicianId: string) => technicians.find(item => item.id === technicianId);
  if (plan === "proposal") {
    const route = proposal?.routes.find(item => item.stops.some(stop => stop.appointmentId === id));
    if (route === undefined) return null;
    const stops = [...route.stops].sort((a, b) => a.sequence - b.sequence);
    const index = stops.findIndex(stop => stop.appointmentId === id);
    const stop = stops[index], before = stops[index - 1], tech = techOf(route.technicianId);
    if (stop === undefined || tech === undefined) return null;
    const previous = before === undefined ? null : { customerName: byId.get(before.appointmentId)?.customerName ?? before.appointmentId, plannedEnd: before.plannedEnd };
    return { technicianName: tech.name, technicianColor: tech.color, stopNumber: stop.sequence + 1, stopCount: stops.length,
      plannedStart: stop.plannedStart, plannedEnd: stop.plannedEnd, previous, plan };
  }
  const appointment = byId.get(id);
  if (appointment === undefined) return null;
  const tech = techOf(appointment.technicianId);
  if (tech === undefined) return null;
  const route = appointments.filter(item => item.technicianId === appointment.technicianId).sort((a, b) => a.sequence - b.sequence);
  const before = route[route.findIndex(item => item.id === id) - 1];
  return { technicianName: tech.name, technicianColor: tech.color, stopNumber: appointment.sequence + 1, stopCount: route.length,
    plannedStart: appointment.plannedStart, plannedEnd: appointment.plannedEnd,
    previous: before === undefined ? null : { customerName: before.customerName, plannedEnd: before.plannedEnd }, plan };
}
