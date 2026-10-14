"""Load the documents that define the stateless API service: the OpenAPI contract, the design document, the
integration guide, the operations runbook and the host conformance kit.

Every table in the report is read from these files when the report is built, so the report cannot drift from them
without the verifier noticing. Nothing is typed in except the pull request history, which is recorded here from the
repository's merged pull requests as listed on 2026-10-14.
"""
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "docs/api/openapi-v1.yaml"
DESIGN = ROOT / "docs/stateless-api-design.md"
GUIDE = ROOT / "docs/integration-guide.md"
RUNBOOK = ROOT / "docs/operations-runbook.md"
CASES = ROOT / "infra/host-conformance/src/cases.ts"

# The merged pull requests of the stateless API work, from the repository's pull request list on 2026-10-14:
# (number, merged date, phase, title). The phase names follow docs/stateless-api-design.md.
PULL_REQUESTS = (
    (70, "2026-10-07", "Design", "Propose the stateless scheduling API and thin store design"),
    (76, "2026-10-08", "Design", "Record integration, deployment, tenancy and route cache decisions"),
    (80, "2026-10-08", "S1 hardening", "Run each overnight metro-day once per night across scheduler replicas"),
    (81, "2026-10-08", "S1 hardening", "Route remote solve cancellation to the replica running the solve"),
    (82, "2026-10-08", "S1 hardening", "Trim the persistent route cache until it is within its cap"),
    (83, "2026-10-08", "S1 hardening", "Require a shared bearer token on routing-service computation endpoints"),
    (85, "2026-10-08", "S2 contract", "Add tenants and per-tenant API tokens for the public /api/v1 surface"),
    (86, "2026-10-08", "S2 contract", "Add the draft OpenAPI 3.1 contract for the public /api/v1 surface"),
    (88, "2026-10-08", "S2 contract", "Add strict Java types for the public /api/v1 contract"),
    (89, "2026-10-08", "S3 daily", "Build the daily solver input from a public request"),
    (91, "2026-10-08", "S3 daily", "Route public daily requests through their own metro's routing service"),
    (92, "2026-10-08", "S3 daily", "Record the address quality rules for the public API"),
    (93, "2026-10-08", "S3 daily", "Standardize address formatting edge cases before matching"),
    (94, "2026-10-08", "S3 daily", "Import US Census TIGER address ranges into Nominatim"),
    (95, "2026-10-08", "S4 thin store", "Thin store for public API requests and daily proposals"),
    (96, "2026-10-08", "S3 daily", "Serve POST /api/v1/daily/proposals"),
    (97, "2026-10-08", "S3 daily", "Let each client set its own fairness budget"),
    (98, "2026-10-08", "S5 commit", "Serve the daily commit endpoint"),
    (99, "2026-10-08", "Booking", "Build booking search facts from a host request"),
    (100, "2026-10-08", "Booking", "Serve POST /api/v1/booking/offers"),
    (101, "2026-10-08", "Booking", "Serve booking select and release; client sends its offer limit"),
    (102, "2026-10-08", "Booking", "Serve booking confirm with the day's snapshot"),
    (103, "2026-10-08", "Booking", "Locate address-only public API locations with Nominatim"),
    (104, "2026-10-08", "Repair", "Serve POST /api/v1/repairs/proposals"),
    (105, "2026-10-08", "S6 portal", "Scope the portal to switchable clients"),
    (106, "2026-10-08", "S6 portal", "Add per-client solver settings"),
    (107, "2026-10-08", "S6 portal", "Build each client's public API snapshot"),
    (108, "2026-10-08", "S6 portal", "Book through the public scheduling API"),
    (109, "2026-10-08", "S6 portal", "Optimize dispatch days through the public scheduling API"),
    (110, "2026-10-08", "S6 portal", "Repair time off through the public scheduling API"),
    (111, "2026-10-08", "S6 portal", "Let two clients serve the same metro"),
    (112, "2026-10-08", "S6 portal", "Run each client's overnight optimization from the portal"),
    (113, "2026-10-09", "P7 retirement", "Change depots and depot assignments through the public API"),
    (114, "2026-10-09", "P7 retirement", "Cancel appointments and draw dispatch roads through the public API"),
    (115, "2026-10-09", "P7 retirement", "Generate and clear fake data through the public API"),
    (116, "2026-10-09", "P7 retirement", "Run the booking test runner through the public API"),
    (117, "2026-10-09", "P7 retirement", "Book only through the public API"),
    (118, "2026-10-09", "P7 retirement", "Run dispatch, fake data and the test runner only through the public API"),
    (119, "2026-10-09", "P7 retirement", "Schedule everything through the public API and remove SCHEDULER_PUBLIC_API"),
    (120, "2026-10-09", "P7 retirement", "Retire the campaign workflow benchmark layer"),
    (121, "2026-10-09", "P7 retirement", "Retire the booking benchmarks and /internal/benchmark"),
    (122, "2026-10-09", "P7 retirement", "Delete the scheduler's internal booking endpoints"),
    (123, "2026-10-09", "P7 retirement", "Delete the remaining portal-only scheduler endpoints"),
    (125, "2026-10-10", "P8 cleanup", "Drop the tables only the retired scheduler paths used"),
    (126, "2026-10-10", "P8 cleanup", "Remove code left unused by the move to the public API"),
    (127, "2026-10-10", "P8 cleanup", "Update the stateless API design doc for the finished move"),
    (131, "2026-10-12", "S6 portal", "Show the scheduling API connection and provision local tokens"),
    (132, "2026-10-12", "Client policy", "Let each client choose its arrival window length (1 to 8 hours)"),
    (133, "2026-10-12", "Client policy", "Let each client choose its daily search method and search times"),
    (138, "2026-10-13", "Contract gaps", "Keep insertion offers when a refinement fails validation and report policy codes"),
    (139, "2026-10-13", "Contract gaps", "Make every policy rule the client's own and drop omaha_setting"),
    (140, "2026-10-13", "Contract gaps", "Serve host cancellation of running calculations"),
    (141, "2026-10-13", "Contract gaps", "Record where hold and geometry coordinates came from"),
    (142, "2026-10-13", "Integration", "Harden scheduler operations: readiness, logs, metrics, retention, least-privilege login"),
    (143, "2026-10-13", "Integration", "The scheduler owns its database; the portal becomes a pure API client"),
    (149, "2026-10-13", "Integration", "Host conformance kit: prove a scheduler over its public API"),
    (150, "2026-10-13", "Integration", "Integration guide and public contract 1.0.0"),
    (151, "2026-10-13", "Performance", "API load tool, load measurement, and a booking 500 fix"),
    (152, "2026-10-14", "Performance", "Keep holds when late snapshots arrive and let concurrent bookings publish"),
    (153, "2026-10-14", "Performance", "Refuse daily proposals early with Retry-After and route them before the slot"),
    (154, "2026-10-14", "Performance", "Report searchComplete false only when a needed pass was cut short"),
    (155, "2026-10-14", "Performance", "Report which dates a booking search finished, and shed its optional refinement"),
    (156, "2026-10-14", "Performance", "Let a remote solve wait for its slot, and answer 503 when the remote solver cannot calculate"),
    (157, "2026-10-14", "Performance", "Queue daily and repair proposals (Prefer: respond-async) with per-client fair queuing"),
    (158, "2026-10-14", "Performance", "Run booking and daily replica pools behind one load balancer, and cancel from any replica"),
)


def markdown_table(text, start, end):
    """The rows of the first markdown table between two markers, as lists of cell strings."""
    segment = text[text.index(start):text.index(end, text.index(start))]
    rows = [line for line in segment.splitlines() if line.startswith("| ")]
    assert rows and rows[1].startswith("| ---"), start
    cells = [[c.strip() for c in row.strip()[1:-1].split("|")] for row in (rows[0], *rows[2:])]
    width = len(cells[0])
    assert all(len(r) == width for r in cells), (start, [len(r) for r in cells])
    return cells[0], cells[1:]


def plain(text):
    """Markdown inline code and emphasis removed, for table cells."""
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text.replace("`", "")).replace("[docs/integration-guide.md](integration-guide.md)", "docs/integration-guide.md")


def load():
    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    operations = []
    for path, item in spec["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "delete", "put"):
                assert op["x-implementation"] == "implemented", op["operationId"]
                operations.append({"method": method.upper(), "path": path, "id": op["operationId"], "summary": op["summary"], "responses": sorted(op["responses"])})
    history = re.findall(r"^- (\d+\.\d+\.\d+) \((\d{4}-\d{2}-\d{2})\): (.+?)(?=^- \d|\Z)", spec["info"]["description"].split("Version history:")[1], re.M | re.S)
    history = [(v, d, " ".join(t.split())) for v, d, t in history]
    assert history[0][0] == spec["info"]["version"]
    errors = spec["components"]["schemas"]["Problem"]["properties"]["error"]["enum"]
    design = DESIGN.read_text(encoding="utf-8")
    guide = GUIDE.read_text(encoding="utf-8")
    runbook = RUNBOOK.read_text(encoding="utf-8")
    cases = re.findall(r'name: "([^"]+)"', CASES.read_text(encoding="utf-8"))
    assert len(cases) == 27 and len(operations) == 12 and len(errors) == 10
    endpoints = markdown_table(design, "### Endpoints", "### Contract changes")
    changes = markdown_table(design, "### Contract changes", "### Latency target")
    stored = markdown_table(design, "### What is stored", "### Tenant isolation")
    tables = markdown_table(design, "## Thin store", "Every key starts with")
    tiers = markdown_table(design, "## Route cache", "How a lookup works")
    settings = markdown_table(runbook, "## Settings", "## Install")
    problems = markdown_table(guide, "## 7. Errors, idempotency and retries", "**Timeouts to set at the caller")
    ownership = markdown_table(guide, "## 1. Topology and ownership", "Every calculating call carries a **snapshot**")
    assert len(endpoints[1]) == 10 and len(changes[1]) == 27 and len(problems[1]) == 11 and len(tiers[1]) == 4
    return {"spec": spec, "version": spec["info"]["version"], "operations": operations, "history": history, "errors": errors,
            "schemas": sorted(spec["components"]["schemas"]), "cases": cases, "endpoints": endpoints, "changes": changes, "stored": stored,
            "tables": tables, "tiers": tiers, "settings": settings, "problems": problems, "ownership": ownership, "pull_requests": PULL_REQUESTS}


if __name__ == "__main__":
    d = load()
    print(d["version"], len(d["operations"]), "operations", len(d["schemas"]), "schemas", len(d["errors"]), "errors", len(d["history"]), "versions",
          len(d["cases"]), "cases", len(d["changes"][1]), "contract changes", len(d["settings"][1]), "settings", len(d["pull_requests"]), "pull requests")
