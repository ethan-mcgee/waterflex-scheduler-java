/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Next 16 blocks dev-server resources requested from another origin. The browser tests and local use reach
  // `next dev` at 127.0.0.1, which Next does not treat as its own origin. This setting has no effect on `next start`.
  allowedDevOrigins: ["127.0.0.1"],
};

module.exports = nextConfig;
