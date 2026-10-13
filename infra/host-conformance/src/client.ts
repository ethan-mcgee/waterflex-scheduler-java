// The kit's only connection to the scheduler: HTTPS (or HTTP on a private network) to /api/v1 with the tenant's bearer
// token, exactly what a host has. Bodies are sent as text so a case can send JSON a serializer would never produce.

export const TOKEN_PATTERN = /^wfs_[A-Za-z0-9_-]{43}$/;

export interface Reply {
  readonly status: number;
  readonly headers: Headers;
  readonly text: string;
  /** The parsed body; null when the body was empty, undefined when it was not JSON. */
  readonly json: unknown;
  readonly ms: number;
}

export interface CallOptions {
  /** The token to send instead of the kit's; null sends no Authorization header. */
  readonly token?: string | null;
  readonly timeoutMs?: number;
}

export class SchedulerApi {
  readonly #base: string;
  readonly #token: string;

  constructor(base: string, token: string) {
    let url: URL;
    try {
      url = new URL(base);
    } catch {
      throw new Error(`--url is not a URL: ${base}`);
    }
    if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("--url must be http or https");
    if (url.search !== "" || url.hash !== "") throw new Error("--url takes no query or fragment");
    if (!TOKEN_PATTERN.test(token)) throw new Error("The token file does not hold a scheduler token (wfs_ followed by 43 characters)");
    this.#base = url.toString().replace(/\/+$/, "");
    this.#token = token;
  }

  get base(): string {
    return this.#base;
  }

  async call(method: "GET" | "POST" | "DELETE", path: string, body?: string | object, options: CallOptions = {}): Promise<Reply> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const token = options.token === undefined ? this.#token : options.token;
    if (token !== null) headers.Authorization = `Bearer ${token}`;
    let payload: string | undefined;
    if (body !== undefined) {
      payload = typeof body === "string" ? body : JSON.stringify(body);
      headers["Content-Type"] = "application/json";
    }
    const started = performance.now();
    const response = await fetch(`${this.#base}${path}`, { method, headers, body: payload ?? null,
      signal: AbortSignal.timeout(options.timeoutMs ?? 30_000) });
    const text = await response.text();
    let json: unknown = null;
    if (text !== "") {
      try {
        json = JSON.parse(text) as unknown;
      } catch {
        json = undefined;
      }
    }
    return { status: response.status, headers: response.headers, text, json, ms: Math.round(performance.now() - started) };
  }
}
