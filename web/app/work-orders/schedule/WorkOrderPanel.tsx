"use client";

import { useId, useState, type FormEvent } from "react";
import { errorMessage, readResponse, workOrderSaved } from "@/lib/contracts";
import { MAX_DURATION_MIN, MAX_REASON_CODES, MIN_DURATION_MIN, suggestedMinutes } from "@/lib/reasonCodesCore";
import type { WorkOrderFields } from "@/lib/workOrdersDayCore";
import styles from "./calendar.module.css";

export interface ReasonCodeChoice { code: string; name: string; estDurationMin: number; bufferPct: number }

/** The time allotted as the panel says it: who set it, and the suggestion when there was one. */
function allottedLine(workOrder: WorkOrderFields): string {
  if (workOrder.suggestedDurationMin === null) return `${workOrder.durationMin} min (taken in before reason codes)`;
  if (workOrder.durationOverridden) return `${workOrder.durationMin} min, set by the dispatcher (suggested ${workOrder.suggestedDurationMin} min)`;
  return `${workOrder.durationMin} min, suggested by the reason codes`;
}

const callFirstText = (value: boolean | null) => value === null ? "Not recorded" : value ? "Yes" : "No";
const shown = (value: string | null) => value ?? "None";

/**
 * A work order's fields, and a form to edit them. `codeOptions` is given only while the work order waits for the
 * calendar: then its reason codes and time allotted can change too. A booked visit's day was planned with them.
 */
export default function WorkOrderPanel({ jobId, workOrder, codeOptions, onSaved }: {
  jobId: string;
  workOrder: WorkOrderFields;
  codeOptions: readonly ReasonCodeChoice[] | null;
  onSaved: () => void;
}) {
  const id = useId();
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [type, setType] = useState(workOrder.workOrderType ?? "");
  const [purchaseOrder, setPurchaseOrder] = useState(workOrder.purchaseOrder ?? "");
  const [request, setRequest] = useState(workOrder.customerRequest ?? "");
  const [message, setMessage] = useState(workOrder.technicianMessage ?? "");
  const [callFirst, setCallFirst] = useState<"" | "yes" | "no">(workOrder.callFirst === null ? "" : workOrder.callFirst ? "yes" : "no");
  const [codes, setCodes] = useState<string[]>(workOrder.reasonCodes.map(code => code.code));
  const [allotted, setAllotted] = useState<string | null>(workOrder.durationOverridden ? String(workOrder.durationMin) : null);

  function open() {
    setType(workOrder.workOrderType ?? ""); setPurchaseOrder(workOrder.purchaseOrder ?? ""); setRequest(workOrder.customerRequest ?? "");
    setMessage(workOrder.technicianMessage ?? ""); setCallFirst(workOrder.callFirst === null ? "" : workOrder.callFirst ? "yes" : "no");
    setCodes(workOrder.reasonCodes.map(code => code.code)); setAllotted(workOrder.durationOverridden ? String(workOrder.durationMin) : null);
    setError(null); setEditing(true);
  }

  const chosen = codeOptions === null ? [] : codes.flatMap(code => codeOptions.find(option => option.code === code) ?? []);
  const suggested = chosen.length > 0 && chosen.length === codes.length ? suggestedMinutes(chosen) : null;
  const typedMinutes = allotted !== null && /^\d+$/.test(allotted.trim()) ? Number(allotted.trim()) : null;
  const timingProblem = codeOptions === null ? null
    : codes.some(code => code === "") ? "Choose each reason code, or remove it."
    : allotted !== null && (typedMinutes === null || typedMinutes < MIN_DURATION_MIN || typedMinutes > MAX_DURATION_MIN)
      ? `Time allotted must be a whole number of minutes from ${MIN_DURATION_MIN} to ${MAX_DURATION_MIN}.`
    : allotted === null && suggested !== null && suggested > MAX_DURATION_MIN ? `These reason codes add up to ${suggested} minutes. Enter a shorter time allotted.`
    : null;

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (timingProblem !== null) return;
    const text = (value: string) => value.trim() === "" ? null : value.trim();
    const body = { workOrderType: text(type), purchaseOrder: text(purchaseOrder), customerRequest: text(request), technicianMessage: text(message),
      callFirst: callFirst === "" ? null : callFirst === "yes",
      ...(codeOptions === null ? {} : { timing: { reasonCodes: codes, durationMin: allotted === null ? null : typedMinutes } }) };
    setSaving(true); setError(null);
    try {
      await readResponse(await fetch(`/api/work-orders/${encodeURIComponent(jobId)}`, { method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body) }), workOrderSaved);
      setEditing(false);
      onSaved();
    } catch (failure) {
      setError(errorMessage(failure, "The work order could not be saved. Try again."));
    } finally {
      setSaving(false);
    }
  }

  if (!editing) {
    return <section className={styles.workOrder} aria-labelledby={`${id}-title`} data-work-order={jobId}>
      <div className={styles.workOrderHead}>
        <h3 id={`${id}-title`}>Work order</h3>
        <button type="button" className={styles.textButton} onClick={open}>Edit work order</button>
      </div>
      <dl className={styles.detailList}>
        <dt>Reason codes</dt>
        <dd><ol className={styles.codeList}>{workOrder.reasonCodes.map(code => <li key={code.code}>{code.name} <small>({code.code})</small></li>)}</ol></dd>
        <dt>Time allotted</dt>
        <dd>{allottedLine(workOrder)}</dd>
        <dt>Work order type</dt>
        <dd>{shown(workOrder.workOrderType)}</dd>
        <dt>Purchase order</dt>
        <dd>{shown(workOrder.purchaseOrder)}</dd>
        <dt>Call first</dt>
        <dd>{callFirstText(workOrder.callFirst)}</dd>
        <dt>Customer request</dt>
        <dd>{shown(workOrder.customerRequest)}</dd>
        <dt>Message to the technician</dt>
        <dd>{shown(workOrder.technicianMessage)}</dd>
      </dl>
    </section>;
  }

  return <form className={styles.workOrderForm} onSubmit={save} aria-label="Edit work order">
    <h3>Edit work order</h3>
    {codeOptions === null
      ? <p className={styles.subtle}>A booked visit keeps its reason codes and time allotted: its day was planned with them.</p>
      : <fieldset className={styles.codeFields}>
        <legend>Reason codes</legend>
        {codes.map((code, index) => <div key={index} className={styles.codeField}>
          <select className={styles.select} aria-label={index === 0 ? "Reason code" : `Reason code ${index + 1}`} value={code}
            onChange={event => setCodes(current => current.map((item, at) => at === index ? event.target.value : item))}>
            <option value="">Choose a reason code</option>
            {codeOptions.filter(option => option.code === code || !codes.includes(option.code)).map(option =>
              <option key={option.code} value={option.code}>{option.name}, {option.estDurationMin} min</option>)}
          </select>
          {index > 0 && <button type="button" className={styles.textButton} onClick={() => setCodes(current => current.filter((_, at) => at !== index))}>
            Remove</button>}
        </div>)}
        {codes.length < MAX_REASON_CODES && codes.every(code => code !== "") &&
          <button type="button" className={styles.textButton} onClick={() => setCodes(current => [...current, ""])}>Add another reason code</button>}
        <label>Time allotted (minutes)
          <input className={styles.select} inputMode="numeric" value={allotted ?? (suggested === null ? "" : String(suggested))}
            onChange={event => setAllotted(event.target.value)} />
        </label>
        <span className={styles.subtle}>
          {suggested === null ? "Choose the reason codes to see the suggested time." : `Suggested from the reason codes: ${suggested} min.`}
          {allotted !== null && suggested !== null && <> <button type="button" className={styles.textButton} onClick={() => setAllotted(null)}>Use the suggested time</button></>}
        </span>
      </fieldset>}
    <label>Work order type<input className={styles.select} maxLength={80} value={type} onChange={event => setType(event.target.value)} /></label>
    <label>Purchase order<input className={styles.select} maxLength={80} value={purchaseOrder} onChange={event => setPurchaseOrder(event.target.value)} /></label>
    <label>Call first
      <select className={styles.select} value={callFirst} onChange={event => setCallFirst(event.target.value === "yes" ? "yes" : event.target.value === "no" ? "no" : "")}>
        {workOrder.callFirst === null && <option value="">Not recorded</option>}
        <option value="yes">Yes</option>
        <option value="no">No</option>
      </select>
    </label>
    <label>Customer request<textarea className={styles.select} rows={2} maxLength={1000} value={request} onChange={event => setRequest(event.target.value)} /></label>
    <label>Message to the technician<textarea className={styles.select} rows={2} maxLength={1000} value={message} onChange={event => setMessage(event.target.value)} /></label>
    {timingProblem !== null && <p className={styles.deleteError} role="alert">{timingProblem}</p>}
    {error !== null && <p className={styles.deleteError} role="alert">{error}</p>}
    <div className={styles.panelActions}>
      <button className={styles.button} type="submit" disabled={saving || timingProblem !== null}>{saving ? "Saving..." : "Save work order"}</button>
      <button className={styles.button} type="button" disabled={saving} onClick={() => setEditing(false)}>Cancel</button>
    </div>
  </form>;
}
