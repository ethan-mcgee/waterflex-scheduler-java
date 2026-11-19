"use client";

import { useEffect, useRef } from "react";

/**
 * Runs `abort` when the component leaves the page, to stop the requests it still has out.
 *
 * In development React's Strict Mode rehearses an unmount: it runs every effect's cleanup and then its setup again,
 * keeping the component's state. A press or click that lands while the page hydrates can start a request just before
 * that rehearsal, and a plain cleanup would cancel it while the state still waits for its answer. So the cleanup only
 * notes that the component left, and the abort runs a microtask later, once it is clear the component did not come
 * straight back.
 */
export function useAbortOnUnmount(abort: () => void) {
  const latest = useRef(abort);
  useEffect(() => { latest.current = abort; }, [abort]);
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      queueMicrotask(() => { if (!mounted.current) latest.current(); });
    };
  }, []);
}
