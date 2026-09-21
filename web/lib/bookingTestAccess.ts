// This switch is deliberately independent of NODE_ENV: local Docker uses a production build.
export function bookingTestsEnabled() { return process.env.LOCAL_BOOKING_TESTS === "true"; }
export function localTestRequestAllowed(request: Request) {
  if (!bookingTestsEnabled()) return false;
  const url = new URL(request.url);
  const hostHeader = request.headers.get("host");
  let externalUrl = url;
  if (hostHeader) {
    try {
      externalUrl = new URL(`${url.protocol}//${hostHeader}`);
    } catch {
      return false;
    }
  }
  if (!["localhost", "127.0.0.1", "[::1]"].includes(externalUrl.hostname)) return false;
  const origin = request.headers.get("origin");
  return (!origin || origin === externalUrl.origin) && request.headers.get("sec-fetch-site") !== "cross-site";
}
