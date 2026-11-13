# Scheduler sandbox for WaterFlex Software developers

The sandbox runs the scheduler on a developer's own machine, so WaterFlex Software can build and test its integration before the scheduler VM exists. It is the real scheduler: the same image, proxy, booking and daily pools, database and migrations as the VM ([deploy/vm/docker-compose.yml](../deploy/vm/docker-compose.yml)). Only the metro's road graph is replaced, by a fixture router that knows a fixed set of points, so the sandbox needs no map and starts in seconds. The [integration guide](integration-guide.md) explains every call; this page gets you to the first ones.

## What differs from the VM

- **Roads:** The fixture router routes only the 15 points listed under "Fixture points", and times every leg between two different points at 10 minutes and 3 km. Build every location in a sandbox snapshot from those points.
- **Unknown coordinates:** A snapshot with any other coordinate gets 503 `ROUTING_UNAVAILABLE` with `retryable: true`. Only the sandbox answers that for this reason; do not retry it, use a fixture point.
- **Metro:** The one served metro is `sandbox`. Locations must carry coordinates (`GEOCODING_MODE=COORDINATES_REQUIRED`).
- **Transport:** Plain HTTP at `http://127.0.0.1:18090/api/v1`, and HTTPS at `https://127.0.0.1:18091/api/v1` with a self-signed certificate the sandbox makes for `localhost` and `127.0.0.1` ("Call it over HTTPS"). The VM answers over HTTPS only, with a certificate your systems trust.
- **Retention:** Requests, proposals, offers and receipts are purged after 7 days.
- **Tokens:** Sandbox tokens work only on the sandbox that issued them. The VM's operator issues your real tokens.

## Start it

Install Docker with the Compose plugin and give it about 3 GB of memory. From the root of this repository, build and start the stack; the first build takes a few minutes:

```powershell
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env up -d --build
```

The settings are in [deploy/vm/sandbox.env](../deploy/vm/sandbox.env). None of them is a secret, and none may be used on the VM.

## Get a token

Each WaterFlex Software client is one tenant with its own token. Issue one for a sandbox tenant (the arguments are the tenant ID, its name and a label for the token):

```powershell
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env run --rm scheduler-admin --command=issue-token acme-dev "Acme Water (sandbox)" laptop
```

The token is printed once; the scheduler keeps only its digest:

```text
Token issued for tenant acme-dev. Store it now; it cannot be shown again.
id: 1d480b2a-82e8-4c75-8d78-70adf8c16b42
token: wfs_<43 characters>
```

Keep it where your application reads secrets, never in a repository. `--command=revoke-token <id>` revokes it.

## First calls

Check the token. Every call carries it as a bearer token:

```http
GET /api/v1/whoami HTTP/1.1
Host: 127.0.0.1:18090
Authorization: Bearer wfs_<token>
```

The answer names the tenant, and every answer names the contract version that served it:

```text
HTTP/1.1 200
Content-Type: application/json
X-Scheduler-Version: 1.11.0

{"tenantId":"acme-dev"}
```

Then time a day as you would send it. `POST /api/v1/routes/evaluate` stores nothing, so it is the safest first snapshot. This one has one technician starting at fixture point 1 and one appointment at point 2:

```http
POST /api/v1/routes/evaluate HTTP/1.1
Host: 127.0.0.1:18090
Authorization: Bearer wfs_<token>
Content-Type: application/json
```

```json
{
  "serviceDate": "2026-11-17",
  "snapshot": {
    "metroId": "sandbox",
    "timeZone": "America/Chicago",
    "rates": { "regularHourly": "20", "overtimeHourly": "30", "mileagePerMile": "0.67", "travelBufferPct": "0.2", "travelBufferMinutes": 2 },
    "policy": { "fairnessBudget": "0.02", "dailySearch": "TABU_KOPT", "costSearchSeconds": 10, "fairnessSearchSeconds": 5, "bookingDeadlineMs": 5000, "regularWindowThreshold": 2 },
    "technicians": [ { "id": "tech-1", "qualifications": ["softener-service"] } ],
    "technicianDays": [
      {
        "technicianId": "tech-1",
        "serviceDate": "2026-11-17",
        "lastModified": "2026-11-12T21:04:17Z",
        "shift": { "start": "2026-11-17T08:00:00-06:00", "end": "2026-11-17T17:00:00-06:00" },
        "absences": [],
        "start": { "lat": 41.25855, "lng": -95.92929 },
        "end": { "lat": 41.25855, "lng": -95.92929 },
        "maxPaidMinutes": 540
      }
    ],
    "appointments": [
      {
        "id": "appt-1",
        "technicianId": "tech-1",
        "serviceDate": "2026-11-17",
        "serviceId": "softener-service",
        "durationMinutes": 60,
        "window": { "start": "2026-11-17T08:00:00-06:00", "end": "2026-11-17T12:00:00-06:00" },
        "location": { "lat": 41.26088, "lng": -96.18435 },
        "sequence": 0,
        "plannedStart": "2026-11-17T08:20:00-06:00"
      }
    ]
  }
}
```

The scheduler times the route in the order given. The technician leaves at 08:00, drives 10 minutes plus the travel buffer, and starts at 08:14 local time (responses give instants in UTC):

```json
{"feasible":true,"routes":[{"technicianId":"tech-1","serviceDate":"2026-11-17","stops":[{"appointmentId":"appt-1","sequence":0,"plannedStart":"2026-11-17T14:14:00Z","plannedEnd":"2026-11-17T15:14:00Z"}]}],"skippedTechnicianDays":[],"costCents":3183,"overtimeMinutes":0}
```

A refusal says what is wrong and where. Sending `"costSearchSeconds": 2` answers:

```json
{"error":"INVALID_REQUEST","message":"policy.costSearchSeconds must be between 5 and 240 at $.snapshot.policy","retryable":false}
```

Branch on `error`, never on `message`, and retry with the same `requestId` only when `retryable` is true (integration guide, section 7).

From here, follow the integration guide's booking (section 4) and daily (section 5) sequences with dates in the coming days. The conformance kit's [cases](../infra/host-conformance/src/cases.ts) and [snapshots](../infra/host-conformance/src/snapshot.ts) are runnable examples of every call, built from the same fixture points.

## Fixture points

The 15 coordinates the sandbox routes, in the conformance kit's order ([infra/host-conformance/src/points.ts](../infra/host-conformance/src/points.ts)):

| Point | Latitude | Longitude |
| ----: | -------: | --------: |
|     1 | 41.25855 | -95.92929 |
|     2 | 41.26088 | -96.18435 |
|     3 | 41.17933 | -95.91875 |
|     4 | 41.31755 | -95.95176 |
|     5 | 41.23891 | -96.01473 |
|     6 | 41.27959 | -96.23327 |
|     7 | 41.21095 | -95.94185 |
|     8 | 41.29995 | -96.02809 |
|     9 | 41.25246 | -95.96034 |
|    10 | 41.15062 | -96.10707 |
|    11 | 41.23851 | -95.89048 |
|    12 | 41.15440 | -96.04220 |
|    13 | 41.26190 | -95.86080 |
|    14 | 41.28640 | -96.23450 |
|    15 | 41.25240 | -95.99800 |

The router compares coordinates rounded to five decimal places.

## Run the conformance kit

The [host conformance kit](../infra/host-conformance/README.md) exercises every endpoint, sequence and refusal and writes a pass or fail report. It uses its own tenant. Issue its token and pipe it to the kit, so it is never written to a file:

```powershell
$token = (docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env run --rm scheduler-admin --command=issue-token conformance "Conformance kit" sandbox | Select-String '^token: ').Line.Substring(7)
cd infra/host-conformance
npm ci
$token | npx tsx conformance.ts --url http://127.0.0.1:18090 --token-file - --metro sandbox --time-zone America/Chicago --points fixture --out <report-directory>
```

On the sandbox the whole kit takes under a minute.

## Call it over HTTPS

To exercise your client's TLS settings before the VM exists, copy the sandbox's certificate out and trust it in your client. The kit runs on Node, which reads extra trusted certificates from `NODE_EXTRA_CA_CERTS`. From the root of this repository, with `$token` issued as above:

```powershell
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env cp scheduler-proxy:/etc/nginx/tls/fullchain.pem "$env:TEMP\sandbox-ca.pem"
$env:NODE_EXTRA_CA_CERTS = "$env:TEMP\sandbox-ca.pem"
cd infra/host-conformance
$token | npx tsx conformance.ts --url https://127.0.0.1:18091 --token-file - --metro sandbox --time-zone America/Chicago --points fixture --out <report-directory>
```

The certificate is made on the first start and kept until `down -v`. Never turn certificate checks off in your client to reach the sandbox: the VM's certificate is checked the same way.

## Stop or reset it

`down` stops the sandbox and keeps its database, so tenants and tokens survive a restart; `down -v` also deletes the database, and with it every tenant, token and stored answer:

```powershell
docker compose -f deploy/vm/docker-compose.yml -f deploy/vm/sandbox.override.yml --env-file deploy/vm/sandbox.env down -v
```
