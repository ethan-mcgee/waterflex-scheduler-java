/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Next 16 blocks dev-server resources requested from another origin. The browser tests and local use reach
  // `next dev` at 127.0.0.1, which Next does not treat as its own origin. This setting has no effect on `next start`.
  allowedDevOrigins: ["127.0.0.1"],
  // The repository keeps its own AGENTS.md at the root; `next dev` would otherwise write a second one here.
  agentRules: false,
  // Pages moved into the portal's sections keep their old addresses working. The query string carries over.
  async redirects() {
    return [
      // The New work order page became the form on the schedule, opened by ?new=1.
      { source: "/book", destination: "/work-orders/schedule?new=1", permanent: true },
      { source: "/work-orders/new", destination: "/work-orders/schedule?new=1", permanent: true },
      { source: "/dispatch/follow-up", destination: "/work-orders/follow-up", permanent: true },
      { source: "/time-off", destination: "/technicians/time-off", permanent: true },
      { source: "/dealerships", destination: "/branches", permanent: true },
      // The classic schedule became the work orders calendar; its date, week, view, metroId and placeJob mean the same.
      { source: "/schedule", destination: "/work-orders/schedule", permanent: true },
    ];
  },
};

module.exports = nextConfig;
