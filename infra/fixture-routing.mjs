// Deterministic matrix for the isolated booking and optimizer smoke tests.
import { createServer } from "node:http";

const points = new Map([
  ["43.73500,7.42000", 0],
  ["43.74800,7.43800", 1],
]);
const routingIdentity = "ci-monaco-car-v1";

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
  if (request.method !== "POST" || !["/internal/matrix", "/internal/route"].includes(request.url)) {
    response.writeHead(404).end();
    return;
  }
  try {
    let raw = "";
    for await (const chunk of request) {
      raw += chunk;
      if (raw.length > 16_384) throw new Error("Matrix request too large");
    }
    const { origins, destinations, points: routePoints, expectedRoutingIdentity } = JSON.parse(raw);
    if (expectedRoutingIdentity && expectedRoutingIdentity !== routingIdentity) {
      response.writeHead(409, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: "Routing identity changed" }));
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
