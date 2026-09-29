"""Generate reviewable summaries from retained field evidence, without external packages."""
from collections import Counter, defaultdict
from datetime import date
from hashlib import sha256
from html import escape
from pathlib import Path
from statistics import mean
import json
import gzip
import random
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/evidence/scheduler-field-2026-09-29"
OUT = ROOT / "docs/scheduler-field-validation.md"


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def money(cents):
    return f"${cents / 100:,.2f}"


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |"] +
                     ["| " + " | ".join(str(cell) for cell in row) + " |" for row in rows])


def cost(row, phase="after"):
    return sum(day["policy"]["costCents"] for day in row[phase]["days"])


def served_ids(row):
    return tuple(a["index"] for a in row["attempts"] if a["served"])


def bootstrap(values):
    if len(values) < 2:
        return None
    rng = random.Random(592983)
    means = sorted(mean(rng.choices(values, k=len(values))) for _ in range(10000))
    return [means[249], means[9749]]


def main():
    booking, daily, provenance, failures = [], [], [], []
    inventory = []
    for path in sorted([*DATA.glob("*.jsonl"), *DATA.glob("*.jsonl.gz")]):
        raw = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
        inventory.append({"file": path.name, "bytes": len(raw), "sha256": sha256(raw).hexdigest()})
        rows = [json.loads(line) for line in raw.decode("utf-8-sig").splitlines() if line.strip()]
        metadata = next((r for r in rows if r.get("type") == "provenance"), {})
        provenance.append({"file": path.name, "metadata": metadata})
        for row in rows:
            row = dict(row, file=path.name)
            if row.get("type") == "case":
                row["stage"] = next((stage for stage in ("original", "held", "stress") if stage in path.stem), "screen")
                row["seed"] = metadata["seed"]
                assert row["served"] == len(served_ids(row))
                assert row["promiseViolations"] == 0
                assert all(d["policy"]["overtimeMinutes"] == 0 for d in row["after"]["days"]), path
                booking.append(row)
            elif row.get("type") == "result":
                row["budget"] = int(re.search(r"(?:screen|held)-(\d+)", path.name)[1])
                assert row["violations"] == 0 and row["after"]["overtimeMinutes"] == 0
                daily.append(row)
            elif row.get("type") not in ("provenance", "configuration"):
                failures.append(row)
    field = load(DATA / "field-scenarios.json")
    assert len(field["rows"]) == 180
    assert all(r["costCents"] == r["oracleCostCents"] and r["overtimeMinutes"] == 0 for r in field["rows"])
    companions = [load(p) for p in sorted(DATA.glob("field-*.json")) if p.name != "field-scenarios.json"]
    assert all(r["overtimeMinutes"] == 0 for r in companions)
    groups = defaultdict(list)
    for row in booking:
        groups[row["stage"], row["variant"]].append(row)
    summaries = []
    for (stage, variant), rows in sorted(groups.items()):
        attempts = [a for r in rows for a in r["attempts"]]
        summary = dict(stage=stage, variant=variant, cases=len(rows), requests=len(attempts), served=sum(r["served"] for r in rows),
                       incomplete=sum(r["incomplete"] for r in rows), finalCostCents=sum(cost(r) for r in rows),
                       meanCaseP95Ms=mean(r["p95Ms"] for r in rows), conflicts=sum(a["outcome"] == "SCHEDULE_CONFLICT" for a in attempts),
                       outcomes=dict(Counter(a["outcome"] for a in attempts)),
                       cpuSeconds=sum(r["processAfter"]["processCpuNanos"] - r["processBefore"]["processCpuNanos"] for r in rows) / 1e9,
                       peakHeapBytes=max(r["processAfter"]["peakHeapUsedBytes"] for r in rows),
                       routingPairs=sum(r["processAfter"]["routing"]["requestedPairs"] - r["processBefore"]["routing"]["requestedPairs"] for r in rows))
        summaries.append(summary)
    paired = []
    def key(r):
        return r["stage"], r["seed"], r["size"], r["workload"], r["concurrency"], r["cache"], r["datasetFingerprint"]
    controls = {key(r): r for r in booking if r["variant"] == "INSERTION"}
    for stage in ("screen", "held"):
        for variant in sorted({r["variant"] for r in booking} - {"INSERTION"}):
            units, matched, different, served_delta = defaultdict(list), 0, 0, 0
            for row in groups.get((stage, variant), []):
                baseline = controls.get(key(row))
                if baseline is None:
                    continue
                served_delta += row["served"] - baseline["served"]
                if served_ids(row) != served_ids(baseline):
                    different += 1
                    continue
                matched += 1
                units[row["seed"], row["size"], row["workload"]].append(cost(baseline) - cost(row))
            values = [mean(v) for v in units.values()]
            paired.append(dict(stage=stage, variant=variant, equalCustomerCases=matched, differentCustomerCases=different,
                               streamUnits=len(values), servedDelta=served_delta, meanSavingsCents=mean(values) if values else None,
                               exploratory95PercentInterval=bootstrap(values)))
    quality = []
    for (stage, variant), rows in sorted(groups.items()):
        for seconds in (1, 5, 15, 30, 60):
            observed = []
            total = 0
            for row in rows:
                for attempt in row["attempts"]:
                    total += 1
                    trace = [p for p in attempt.get("qualityTrace", []) if p["elapsedMs"] <= seconds * 1000 and p["costDeltaCents"] is not None]
                    if trace:
                        observed.append(trace[-1]["costDeltaCents"])
            quality.append(dict(stage=stage, variant=variant, seconds=seconds, visibleIncumbents=len(observed), searches=total,
                                meanVisibleCostDeltaCents=mean(observed) if observed else None))
    policy_pairs = []
    for row in booking:
        if row["stage"] != "original":
            continue
        matches = [r for r in booking if r["stage"] == "screen" and r["variant"] == row["variant"] and r["datasetFingerprint"] == row["datasetFingerprint"]]
        if len(matches) != 1:
            continue
        current = matches[0]
        policy_pairs.append(dict(variant=row["variant"], seed=row["seed"], size=row["size"], originalServed=row["served"],
                                 policyServed=current["served"], originalCostCents=cost(row), policyCostCents=cost(current),
                                 sameCustomers=served_ids(row) == served_ids(current)))
    summary = {"booking": summaries, "paired": paired, "policyComparison": policy_pairs, "qualityOverTime": quality, "rawFiles": inventory,
               "dailyResults": len(daily), "fieldRows": len(field["rows"]), "companionRows": len(companions),
               "failures": failures, "provenance": provenance}
    (DATA / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    sections = ["# Scheduler policy, search, and field validation",
      "## Decision and scope",
      "Adopt the single-offer, four-hour, zero-new-overtime safety policy and durable search lifecycle. Retain the existing production search selection and daily TABU default. Expanded search remains experimental. No algorithm promotion is justified by this evidence alone: concurrent service outcomes, limited independent geography, and runtime remain material constraints. These are modeled fixture results, not operational savings or a reproduction from customer production records.",
      "Baseline checkout: `c28a24c4c0c1b4614b442c7a362d205cfa7e0db3`. Original behavior is rebuilt in an isolated checkout and recorded separately as `original`; `screen` and `held` use the new policy. Historical reports and artifacts remain unchanged. Algorithm rollback continues through the common policy and reservation validation layer.",
      "## Policy and implementation",
      "New bookings reserve one best-found offer ranked by incremental operating cost, workload balance, earlier promise, and stable identifiers. Arrival promises span four hours and start hourly. Service and return travel must fit regular availability. Existing confirmed dates/windows, actual dated home/depot endpoints, absences, skills, directed routing, holds, and the 06:00 America/Chicago freeze remain hard boundaries. Existing overtime routes retain their facts and are excluded from new work and flagged for follow-up. An already infeasible snapshot still fails closed; it is not silently repaired during booking.",
      "Insertion covers the normal horizon first, including weekend availability inside the weekday-counted horizon. The search only enters five additional weekdays after a completed normal search finds no candidate, then selects the cheapest candidate on the first feasible overflow date. An interrupted search does not establish infeasibility.",
      "Durable start/status/cancel operations use idempotent UUIDs, one active search per job, a database admission cap of 16, two workers per instance, a 15-second lease renewed each second, and a finite two-minute work lifetime. Search runs outside transactions. Publication revalidates under short transactions and permits one fresh-snapshot retry. Offer TTL starts at publication. Browser reload reconnects using an opaque saved pointer; explicit cancellation releases capacity. Lost ownership becomes a terminal incomplete result. Completed-no-candidate, incomplete, cancelled, and failed results remain distinct.",
      "## Tested algorithms and hypotheses",
      table(["Variant", "Work limits / intended effect", "Decision"], [
        ["INSERTION", "Every eligible route and insertion position; policy-only control", "Retain control"],
        ["BOUNDED", "6 routes, depth 2, beam 8, 500 arrangements/window; relocation, swap, reversal", "Retain existing option"],
        ["EXPANDED", "12 routes, depth 3, beam 16, 2,000 arrangements/window; rank moves before truncation", "Experimental"],
        ["RUIN_RECREATE", "Expanded plus removal of 2/3 related visits, constrained/regret-first complete reconstruction", "Experimental"],
        ["SHARED", "Same reconstruction search with shared route evaluation across windows", "Experimental"],
      ]),
      "Ruin-and-recreate counts reconstruction attempts and route/candidate work separately from arrangement limits. Incomplete reconstruction is rejected. Shared evaluation isolates route-evaluation caching; neighborhood ranking caches still exist in the expanded control. Stronger variants are never attributed to an individual move type solely from a combined result. Daily configurations retain their exact XML and phase counters in raw artifacts.",
      "## Booking results",
      table(["Stage", "Variant", "Cases", "Served / requests", "Incomplete", "Mean case p95, ms", "Total modeled final cost"],
            [[s["stage"], s["variant"], s["cases"], f'{s["served"]}/{s["requests"]}', s["incomplete"], f'{s["meanCaseP95Ms"]:,.0f}', money(s["finalCostCents"])] for s in summaries]),
      "Final-cost totals include the existing ten-day workload and differing served customers. They are inventory totals, not a causal savings comparison. The screen has only three requests per case. Clustered jobs coincide with technician homes and have zero routed road seconds; improvements there describe modeled buffer/waiting cleanup. Held-out dispersed cases use GraphHopper 11 and actual nonzero directed road travel where endpoints differ.",
      "### Original baseline versus policy-only controls",
      table(["Variant", "Seed / technicians", "Original / new served", "Original cost", "New policy cost", "Same customers"],
            [[p["variant"], f'{p["seed"]}/{p["size"]}', f'{p["originalServed"]}/{p["policyServed"]}', money(p["originalCostCents"]), money(p["policyCostCents"]), p["sameCustomers"]] for p in policy_pairs]),
      "These pairs have identical seeded fixture fingerprints and request streams. The original policy includes two-hour promises and multiple choices; the new policy uses four-hour promises, one choice, no new overtime, and durable search. This bundled policy/lifecycle comparison cannot attribute its difference to a single policy. The independent field two-by-two experiment isolates promise width from insertion versus rearrangement. Immediate snapshots are compared there; sequential histories are allowed to diverge in this replay.",
      "### Paired comparison against new-policy insertion",
      table(["Stage", "Variant", "Same customers / different customers", "Stream units", "Served delta", "Mean savings per stream", "Exploratory 95% interval"],
            [[p["stage"], p["variant"], f'{p["equalCustomerCases"]}/{p["differentCustomerCases"]}', p["streamUnits"], p["servedDelta"],
              "n/a" if p["meanSavingsCents"] is None else money(p["meanSavingsCents"]),
              "n/a" if p["exploratory95PercentInterval"] is None else " to ".join(money(v) for v in p["exploratory95PercentInterval"])] for p in paired]),
      "Customer identity is the stable request index within an identical seeded dataset, not a random database UUID. Cost pairs with different served identities are excluded from savings estimates and counted explicitly. Cache/concurrency repetitions are averaged within a seed/size/workload stream before a deterministic 10,000-resample percentile bootstrap. Fleet sizes share generated geography and clustered seeds do not vary the geography, so these intervals are exploratory, not population confidence or independent operational replication. Served-demand deterioration blocks promotion even when equal-customer cost improves.",
      "### Search quality over time",
      table(["Stage / variant", "Seconds", "Visible incumbent / searches", "Mean visible incremental cost"],
            [[q["stage"] + "/" + q["variant"], q["seconds"], f'{q["visibleIncumbents"]}/{q["searches"]}', "n/a" if q["meanVisibleCostDeltaCents"] is None else money(q["meanVisibleCostDeltaCents"])] for q in quality if q["stage"] != "original"]),
      "This samples the last non-null polled incumbent at or before each elapsed time, including queue time. A missing incumbent remains missing. A terminal result persists for later checkpoints. Heartbeats are sampled about once per second; this is browser-observable progress, not exact internal time-to-best. Revalidation can invalidate a provisional incumbent. API latency is measured by the harness; full browser performance under concurrent production traffic was not measured. Stress cases add one-second delayed confirmation, explicit abandonment of every third offer, near-capacity schedules, and concurrency five.",
      "## Daily solvers",
      "All eight existing configurations are rerun under the new zero-overtime policy. Fixture roads are deterministic directed legs, not GraphHopper. Development seeds 17/23/41 screen 10/20 technicians; held-out seeds 59/83 cover 5/10/20/50 at equal total 15/30/60-second budgets. A capped solver may finish early. The cost-reference and fairness phases share the total budget. No-new-request daily optimization is the cleanup control."]
    dg = defaultdict(list)
    for row in daily:
        dg[row["budget"], row["variant"]].append(row)
    sections.append(table(["Seconds", "Variant", "Cases", "Mean accepted cost change", "Mean elapsed, ms", "Violations"],
                          [[b, v, len(rs), money(mean(r["after"]["costCents"] - r["before"]["costCents"] for r in rs)),
                            f'{mean(r["elapsedMs"] for r in rs):,.0f}', sum(r["violations"] for r in rs)] for (b, v), rs in sorted(dg.items())]))
    sections += ["## Field scenarios",
      "The independent small-case oracle enumerates assignments, route order, and minute-grid departure/arrival timing without calling production RouteEvaluator. The flagship has directed Omaha-to-town travel of 40 minutes, town-to-Omaha travel of 45 minutes, and five-minute local legs, with 60-minute visits and an 08:00-16:00 regular shift. Both actual route endpoints are Omaha. Rates are $30/hour regular labor, $45/hour overtime labor (prohibited), and $0.67/mile. Arrival-window ends are exclusive. The oracle independently establishes the cheapest offered arrangement across all candidate windows.",
      f"All {len(field['rows'])} sequential steps match their exact optimum: all six booking orders, two/four-hour promises, five variants, and three confirmed requests. Earlier promises remain fixed while internal order/time may change. This simple fixture is already solved by insertion, so it does not reproduce a failure of insertion. Both wider promises and stronger search are separately varied; neither is required for this fixture's optimal grouping.",
      "### A, then B, then C",
      table(["Promise", "Search", "After request", "Route (actual endpoints included)", "Cost", "Drive / wait min", "Return (UTC)", "Crossings"],
            [[r["promiseMinutes"], r["variant"], r["request"], "Omaha > " + " > ".join(r["routes"]["omaha"]) + " > Omaha", money(r["costCents"]),
              f'{r["drivingMinutes"]}/{r["waitingMinutes"]}', r["segments"][0]["segments"][-1]["return"], r["crossings"]]
             for r in field["rows"] if r["bookingOrder"] == ["A", "B", "C"] and r["variant"] in ("INSERTION", "BOUNDED")]),
      "```text\nArrival history: A (town), B (Omaha), C (town)\nBefore C: Omaha home -> A town -> B Omaha -> Omaha home\nAfter C:  Omaha home -> A/C town together -> B Omaha -> Omaha home\nProtected tight B: Omaha home -> A town -> B Omaha -> C town -> Omaha home\n```",
      "A and C can exchange order in an equivalent optimum. Region crossings are diagnostic only. The protected Omaha case correctly makes grouping lose: its narrow existing promise forces a return before C. Removing that appointment or releasing its reservation restores flexibility without phantom capacity.",
      table(["Companion", "Selected routes", "Cost", "Drive / wait min", "Crossings", "Source"],
            [[r["scenario"], "; ".join(k + ": " + ",".join(v) for k, v in r["after"].items()), money(r["costCents"]), f'{r["drivingMinutes"]}/{r["waitingMinutes"]}', r["crossings"], r["source"]] for r in companions]),
      "The reassignment fixture gives each technician 120 paid minutes of daily capacity, existing jobs of 90 and 60 minutes, and a new 50-minute job qualified only on the original technician. Moving the existing jobs between technicians makes the request possible. It is checked with existing two-hour and four-hour promises. Alternating A/B/C/D/E requests remain grouped where their promises allow. Separate assertions reject an unqualified nearby technician, a long rural service plus return beyond regular hours, an unreachable directed return, and a split-availability violation. The nearby technician begins and ends in the town, not an assumed Omaha depot.",
      "Every flagship row retains before-history, selected route, promised and planned timestamps, service durations, driving, waiting, meters, cost, return time, actual endpoint coordinates, coverage, and reconstruction counts. Companion JSON files retain before/after order and promised/planned/return times. Dated depot, cancellation, hold, cutoff, stale-preview, and overflow behavior also has database/API regression coverage. The flagship confirmation is an in-memory sequential snapshot replay; API lifecycle tests separately exercise persisted confirmation. It is not a customer production trace.",
      "## Reproducibility and limitations",
      "Booking artifact `1438506db10605f88e51cfbeeca0900c58da158d1da3e0ed4269e790b121014b` was built from `2258b2a14e8a9ecd085c79ec377448c0555c2b1e`. Some held runtime headers name the later harness checkout `adcda9a`; the jar hash, not that checkout label, identifies the unchanged tested server. Daily frozen classes were copied from `6da6d9341815d7f290cfe34190974fef1ffe2ff3`. Subsequent preview/contract/migration fixes do not change these booking/daily algorithms. Per-file metadata records configurations, seeds, dataset fingerprints, routing identity, and source fingerprints. Original baseline artifacts explicitly name c28a24c.",
      "Local Windows Java 25 runs share one 24-logical-CPU host, PostgreSQL, and routing cache with concurrent benchmark processes and validation. CPU and heap counters are process diagnostics, not isolated per-request CPU or resident memory. Cold mode clears the benchmark cache as implemented by the harness; shared routing infrastructure can still be warm. No wall-clock speedup should be generalized from these runs. Road service identity is retained in each audit. No same-day field replanning, automatic merge, or operational deployment was performed.",
      "Reproduce booking runs with `node infra/run-field-booking-benchmarks.mjs` and the FIELD_* settings in each runtime manifest. Use only fresh schemas in waterflex_test. Run the field oracle with `mvnw -Pnullability -pl scheduler-service clean test -Dtest=FieldScenarioTest`. Rebuild this report with `python infra/report-field-validation.py`. The generator verifies served totals, zero reported promise/constraint violations, and all exact-case cost equalities. Raw failed and incomplete outcomes are retained. Unmeasured cases are not assigned fabricated zero metrics.",
      "## Gates and remaining acceptance boundaries",
      "See `validation.json` for exact executed checks and their results. Required local checks cover strict Java nullability, frontend lint/typecheck, schema contract, browser recovery/cancellation, PostgreSQL worker ownership/restart, confirmation/holds, optimizer apply, and time-off. CI is independently reported in the PR. Broad production representativeness, true browser concurrency latency, and operational savings remain unestablished; retain baseline defaults until held-out evidence satisfies every promotion criterion."]
    text = "\n\n".join(sections) + "\n"
    assert "\u2014" not in text
    OUT.write_text(text, encoding="utf-8")
    # A deliberately small Markdown renderer for this generator's fixed headings/tables/code/paragraphs.
    html, code = [], False
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            html.append("</pre>" if code else "<pre>")
            code = not code
        elif code:
            html.append(escape(line) + "\n")
        elif line.startswith("#"):
            depth = len(line) - len(line.lstrip("#"))
            html.append(f"<h{depth}>{escape(line[depth:].strip())}</h{depth}>")
        elif line.startswith("| "):
            html.append("<div class='scroll'><table>")
            first = True
            while i < len(lines) and lines[i].startswith("| "):
                cells = lines[i].strip("| ").split(" | ")
                if not all(c.strip("- :") == "" for c in cells):
                    tag = "th" if first else "td"
                    html.append("<tr>" + "".join(f"<{tag}>{escape(c)}</{tag}>" for c in cells) + "</tr>")
                    first = False
                i += 1
            html.append("</table></div>")
            continue
        elif line:
            html.append("<p>" + escape(line) + "</p>")
        i += 1
    chart = ["<h2>Screening cost versus runtime</h2><svg viewBox='0 0 800 340' role='img' aria-label='Mean case p95 versus paired savings from insertion'>"]
    screen = [s for s in summaries if s["stage"] == "screen"]
    max_ms = max((s["meanCaseP95Ms"] for s in screen), default=1)
    for index, row in enumerate(screen):
        pair = next((p for p in paired if p["stage"] == "screen" and p["variant"] == row["variant"]), None)
        saving = pair["meanSavingsCents"] if pair and pair["meanSavingsCents"] is not None else 0
        x, y = 80 + 530 * row["meanCaseP95Ms"] / max_ms, 270 - min(220, saving / 10)
        chart.append(f"<circle cx='{x}' cy='{y}' r='6' fill='#087e8b'/><text x='{x + 9}' y='{y + (index % 2) * 14}'>{escape(row['variant'])}</text>")
    chart.append("<path d='M65 25V280H750' fill='none' stroke='#555'/><text x='190' y='320'>Mean case p95 latency (scaled to slowest screen)</text><text x='75' y='20'>Paired modeled savings versus insertion (cents, upward)</text></svg>")
    page = "<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>Scheduler field validation</title><style>body{font:16px/1.55 system-ui;max-width:1180px;margin:40px auto;padding:0 24px;color:#172b3a}h1,h2,h3{line-height:1.2}h2{margin-top:2em;border-top:1px solid #cbd5df;padding-top:1em}table{border-collapse:collapse;font-size:13px;width:100%}th,td{padding:9px;border:1px solid #cbd5df;text-align:left}th{background:#e8f2f4}tr:nth-child(even){background:#f7f9fb}.scroll{overflow-x:auto}pre{background:#f0f4f7;padding:20px;overflow:auto}svg{width:100%;max-height:380px}svg text{font-size:12px}</style><main>" + "".join(chart) + "\n".join(html) + "</main></html>"
    OUT.with_suffix(".html").write_text(page, encoding="utf-8")
    print(json.dumps({"bookingCases": len(booking), "dailyCases": len(daily), "fieldRows": 180, "companions": len(companions), "summary": str(DATA / "summary.json")}))


if __name__ == "__main__":
    main()
