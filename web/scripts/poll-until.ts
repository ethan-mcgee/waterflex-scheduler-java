import { performance } from "node:perf_hooks";

/** Poll durable outcomes within a wall-clock budget, retaining useful timeout evidence. */
export async function pollUntil<T>(label: string, read: () => Promise<T>, done: (state: T) => boolean,
  timeoutMs = 90_000, intervalMs = 250): Promise<T> {
  const deadline = performance.now() + timeoutMs;
  for (;;) {
    const state = await read();
    if (done(state)) return state;
    const remaining = deadline - performance.now();
    if (remaining <= 0) throw new Error(`${label} timed out after ${timeoutMs}ms; last state: ${JSON.stringify(state)}`);
    await new Promise(resolve => setTimeout(resolve, Math.min(intervalMs, remaining)));
  }
}
