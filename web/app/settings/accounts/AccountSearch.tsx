"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { z } from "zod";
import { accountSearchResponse, errorMessage, readResponse, type waterflexAccount } from "@/lib/contracts";
import { useAbortOnUnmount } from "@/app/portal/useAbortOnUnmount";
import { Panel } from "@/app/portal/ui";
import { TextField } from "@/app/portal/ui/fields";
import styles from "../settingsTable.module.css";

type Account = z.infer<typeof waterflexAccount>;

const SEARCH_DELAY_MS = 250;
const MIN_QUERY = 2;

/** Searches the client's accounts by number, name or street, and lists what matches with where WaterFlex placed it. */
export default function AccountSearch() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<{ query: string; accounts: Account[] } | null>(null);
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

  const term = query.trim();
  const shown = results !== null && results.query === term ? results.accounts : null;
  return <>
    <div className={styles.search}><TextField label="Search accounts" value={query} onChange={setQuery} placeholder="Account number, name or street" /></div>
    {error !== null && <p className={styles.error} role="alert">{error}</p>}
    {term.length >= MIN_QUERY && shown !== null && <Panel className={styles.panel} label="Matching accounts">
      <table className={styles.table}>
        <thead><tr><th>Account</th><th>Name</th><th>Address</th><th>Location</th></tr></thead>
        <tbody>{shown.length === 0 ? <tr><td colSpan={4}>No account matches &ldquo;{term}&rdquo;.</td></tr> : shown.map(account => <tr key={account.id}>
          <td>{account.accountNumber}</td>
          <td>{account.name ?? "Not given"}<small>{[account.phone, account.email].filter(item => item !== null).join(" · ")}</small></td>
          <td>{account.line1}{account.line2 !== null && `, ${account.line2}`}<small>{account.city}, {account.state} {account.postalCode}</small></td>
          <td>{account.location === null ? "No WaterFlex coordinates (follow-up only)" : `${account.location.lat.toFixed(5)}, ${account.location.lng.toFixed(5)}`}</td>
        </tr>)}</tbody>
      </table>
    </Panel>}
  </>;
}
