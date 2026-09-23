// Deterministic matrix for the isolated booking and optimizer smoke tests.
import { createServer } from "node:http";

const points = new Map([
  ["43.73500,7.42000", 0],
  ["43.74800,7.43800", 1],
  // Known Omaha sample locations and seeded technician homes, never a general road fallback.
  ["41.25855,-95.92929", 2], ["41.26088,-96.18435", 3],
  ["41.17933,-95.91875", 4], ["41.31755,-95.95176", 5],
  ["41.23891,-96.01473", 6], ["41.27959,-96.23327", 7],
  ["41.21095,-95.94185", 8], ["41.29995,-96.02809", 9],
  ["41.25246,-95.96034", 10], ["41.15062,-96.10707", 11],
  ["41.23851,-95.89048", 12], ["41.15440,-96.04220", 13],
  ["41.26190,-95.86080", 14], ["41.28640,-96.23450", 15],
]);
const routingIdentity = "ci-monaco-omaha-car-v2";

function index(point) {
  if (!point || typeof point.lat !== "number" || typeof point.lng !== "number") return undefined;
  return points.get(`${point.lat.toFixed(5)},${point.lng.toFixed(5)}`);
}

const server = createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ ready: true, mapVersion: "ci-monaco-v1", routingIdentity,
      profile: "car", engineVersion: "fixture-1" }));
    return;
  }
  if (request.method !== "POST" || !["/internal/matrix", "/internal/route", "/internal/legs"].includes(request.url)) {
    response.writeHead(404).end();
    return;
  }
  try {
    let raw = "";
    for await (const chunk of request) {
      raw += chunk;
      if (raw.length > 131_072) throw new Error("Matrix request too large");
    }
    const { origins, destinations, points: routePoints, pairs, expectedRoutingIdentity } = JSON.parse(raw);
    if (expectedRoutingIdentity && expectedRoutingIdentity !== routingIdentity) {
      response.writeHead(409, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "Routing identity changed" }));
      return;
    }
    if (request.url === "/internal/legs") {
      if (!Array.isArray(pairs) || !pairs.length || pairs.length > 256 || !expectedRoutingIdentity ||
          pairs.some(pair => typeof pair.id !== "string" || index(pair.origin) === undefined || index(pair.destination) === undefined))
        throw new Error("Invalid fixture pairs");
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ routingIdentity, pairs: pairs.map(pair => ({ id: pair.id,
        leg: { routable: true, seconds: index(pair.origin) === index(pair.destination) ? 0 : 600,
          meters: index(pair.origin) === index(pair.destination) ? 0 : 3000 } })) }));
      return;
    }
    if (request.url === "/internal/route") {
      if (!Array.isArray(routePoints) || routePoints.length < 2 || routePoints.length > 65 ||
          routePoints.some((point) => index(point) === undefined)) {
        response.writeHead(422, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ error: "Only fixture coordinates are supported" }));
        return;
      }
      const legs = routePoints.slice(1).map((point, i) => {
        const origin = routePoints[i];
        const same = index(origin) === index(point);
        return { seconds: same ? 0 : 600, meters: same ? 0 : 3000,
          geometry: { type: "LineString", coordinates: same
            ? [[origin.lng, origin.lat], [point.lng, point.lat]]
            : [[origin.lng, origin.lat], [(origin.lng + point.lng) / 2, (origin.lat + point.lat) / 2 + 0.002], [point.lng, point.lat]] } };
      });
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ routingIdentity, legs }));
      return;
    }
    if (!Array.isArray(origins) || !Array.isArray(destinations) || !origins.length || !destinations.length ||
        [...origins, ...destinations].some((point) => index(point) === undefined)) {
      response.writeHead(422, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "Only fixture coordinates are supported" }));
      return;
    }
    const legs = origins.map((origin) => destinations.map((destination) => {
      const same = index(origin) === index(destination);
      return { seconds: same ? 0 : 600, meters: same ? 0 : 3000, routable: true };
    }));
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ mapVersion: "ci-monaco-v1", routingIdentity, legs }));
  } catch {
    response.writeHead(400, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "Invalid matrix request" }));
  }
});

server.listen(18001, "127.0.0.1");
