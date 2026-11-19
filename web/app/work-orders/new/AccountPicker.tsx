"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import type { z } from "zod";
import { accountSearchResponse, errorMessage, readResponse, type waterflexAccount } from "@/lib/contracts";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";
import styles from "./booking.module.css";

export type AccountView = z.infer<typeof waterflexAccount>;

/** How long typing pauses before the accounts are searched. */
const SEARCH_DELAY_MS = 250;
const MIN_QUERY = 2;

export function accountAddress(account: AccountView): string {
  return `${account.line1}${account.line2 === null ? "" : `, ${account.line2}`}, ${account.city}, ${account.state} ${account.postalCode}`;
}

/**
 * Finds the customer's WaterFlex account by its number, name or street, and shows the chosen one with where WaterFlex
 * placed it. An account without WaterFlex coordinates is said to be follow-up only.
 */
export default function AccountPicker({ account, onChoose, disabled }: {
  account: AccountView | null;
  onChoose: (account: AccountView | null) => void;
  disabled: boolean;
}) {
  const inputId = useId();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<{ query: string; accounts: AccountView[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  useAbortOnUnmount(useCallback(() => request.current?.abort(), []));

  useEffect(() => {
    const term = query.trim();
    request.current?.abort();
    if (term.length < MIN_QUERY) return;
    const controller = new AbortController();
    request.current = controller;
    const timer = setTimeout(() => {
      fetch(`/api/accounts?q=${encodeURIComponent(term)}`, { signal: controller.signal, cache: "no-store" })
        .then(response => readResponse(response, accountSearchResponse))
        .then(found => { if (!controller.signal.aborted) { setResults({ query: term, accounts: found.accounts }); setError(null); } })
        .catch((cause: unknown) => { if (!controller.signal.aborted) setError(errorMessage(cause, "Accounts could not be searched. Try again.")); });
    }, SEARCH_DELAY_MS);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query]);

  if (account !== null) {
    return <div className={styles.accountCard} data-account={account.accountNumber}>
      <div>
        <strong>Account {account.accountNumber}</strong>{account.name !== null && <span> · {account.name}</span>}
        <p>{accountAddress(account)}</p>
        {account.location === null
          ? <p className={styles.accountWarning} role="status">WaterFlex has no coordinates for this account. It can only be sent to follow-up.</p>
          : <p className={styles.fieldHint}>Located by WaterFlex</p>}
      </div>
      <button type="button" className={styles.linkButton} disabled={disabled} onClick={() => onChoose(null)}>Change account</button>
    </div>;
  }

  const term = query.trim();
  const shown = results !== null && results.query === term ? results.accounts : null;
  return <div className={styles.field}>
    <label className={styles.label} htmlFor={inputId}>Account</label>
    <input id={inputId} className={styles.input} type="search" value={query} disabled={disabled} autoComplete="off"
      placeholder="Account number, name or street" onChange={event => setQuery(event.target.value)} />
    {error !== null && <div role="alert" className={styles.error}>{error}</div>}
    {term.length >= MIN_QUERY && shown !== null && (shown.length === 0
      ? <p className={styles.fieldHint} role="status">No account matches “{term}”.</p>
      : <ul className={styles.accountResults} aria-label="Matching accounts">
        {shown.map(item => <li key={item.id}>
          <button type="button" disabled={disabled} onClick={() => { onChoose(item); setQuery(""); setResults(null); }}>
            <strong>{item.accountNumber}</strong>{item.name !== null && <span> · {item.name}</span>}
            <small>{accountAddress(item)}{item.location === null ? " · no WaterFlex coordinates" : ""}</small>
          </button>
        </li>)}
      </ul>)}
  </div>;
}
