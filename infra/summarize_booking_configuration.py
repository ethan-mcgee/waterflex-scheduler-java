"""Tables for research/api-load.md from a booking configuration study's evidence (infra/run-booking-configuration-study.ps1).

    python infra/summarize_booking_configuration.py research/evidence/api-load-<date>-booking-configuration.json [--part quality|load|price|capacity|reserve]

Every table is computed from the reports the study kept (quality.ts and load.ts output), never from figures typed in:
quality pairs each variant with the uncapped threshold-1000 search case by case; load reads the caller's latency and
throughput beside the scheduler's own attribution (serverBreakdown, from its request log); capacity sums the clients
of a cell; reserve reads the commit phase against the reserve. Verdicts on the hypotheses are written by hand.
"""
from __future__ import annotations

import argparse
import gzip
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

OFFERS = "POST /api/v1/booking/offers 201"
REFERENCE = "1000"
PROFILES = ("empty", "light", "half", "full")


def read_evidence(path: Path) -> dict:
    # The committed evidence is gzipped: a study of every cell's reports runs to hundreds of megabytes of JSON.
    with (gzip.open(path, "rt", encoding="utf-8-sig") if path.suffix == ".gz" else path.open(encoding="utf-8-sig")) as handle:
        evidence = json.load(handle)
    if not isinstance(evidence.get("cells"), list):
        raise ValueError(f"{path} has no cells: not a booking configuration study")
    return evidence


def cells(evidence: dict, part: str) -> list[dict]:
    return [cell for cell in evidence["cells"] if cell["part"] == part]


def dollars(cents: float | None) -> str:
    return "-" if cents is None else f"{cents / 100:+.2f}"


def mean(values: list[float]) -> float | None:
    return None if not values else statistics.fmean(values)


def median(values: list[float]) -> float | None:
    return None if not values else statistics.median(values)


def fixed(value: float | None, digits: int = 0) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def order_key(part: object) -> tuple:
    """Numbers by value (5 before 10), schedule profiles from empty to full, anything else by its text."""
    if isinstance(part, (int, float)) and not isinstance(part, bool):
        return (0, part, "")
    if part in PROFILES:
        return (1, PROFILES.index(part), "")
    return (2, 0, str(part))


def group(items: list[dict], *keys: str) -> dict[tuple, list[dict]]:
    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for item in items:
        grouped[tuple(item[key] for key in keys)].append(item)
    return dict(sorted(grouped.items(), key=lambda entry: tuple(order_key(part) for part in entry[0])))


def variant_order(names: set[str]) -> list[str]:
    """The reference first, then by threshold and cap."""
    def key(name: str) -> tuple:
        threshold, _, cap = name.partition(":")
        return (name != REFERENCE, int(threshold), -1 if cap == "" else int(cap))
    return sorted(names, key=key)


# Quality: every case of every report in the cells, each variant paired with the reference on the same case.

def quality_pairs(reports: list[dict]) -> dict[str, dict]:
    """Per variant: cases both answered, cost comparisons against the reference, offers and outcomes."""
    tally: dict[str, dict] = {}
    for report in reports:
        for case in report["cases"]:
            by_variant = {entry["variant"]: entry for entry in case["searched"]}
            reference = by_variant.get(REFERENCE)
            for name, entry in by_variant.items():
                stats = tally.setdefault(name, {"searches": 0, "answered": 0, "compared": 0, "same": 0, "earlier": 0, "later": 0, "priced": 0,
                                                "cheaper": 0, "dearer": 0, "changes": [], "refinements": defaultdict(int), "stops": defaultdict(int),
                                                "windows": [], "ms": [], "incomplete": 0, "cost": []})
                stats["searches"] += 1
                if entry["status"] != 201:
                    continue
                stats["answered"] += 1
                stats["refinements"][entry.get("refinement") or "ABSENT"] += 1
                if entry.get("refinementStop"):
                    stats["stops"][entry["refinementStop"]] += 1
                if entry.get("regularWindowsFound") is not None:
                    stats["windows"].append(entry["regularWindowsFound"])
                if entry.get("ms") is not None:
                    stats["ms"].append(entry["ms"])
                if entry.get("searchComplete") is False:
                    stats["incomplete"] += 1
                if entry.get("priced"):
                    stats["cost"].append(entry["priced"]["addedCostCents"])
                if name == REFERENCE or reference is None or reference["status"] != 201:
                    continue
                stats["compared"] += 1
                if signature(entry["offers"]) == signature(reference["offers"]):
                    stats["same"] += 1
                first, base = earliest(entry["offers"]), earliest(reference["offers"])
                if first is not None and (base is None or first < base):
                    stats["earlier"] += 1
                if base is not None and (first is None or first > base):
                    stats["later"] += 1
                if entry.get("priced") and reference.get("priced"):
                    change = entry["priced"]["addedCostCents"] - reference["priced"]["addedCostCents"]
                    stats["priced"] += 1
                    stats["changes"].append(change)
                    if change > 0:
                        stats["dearer"] += 1
                    if change < 0:
                        stats["cheaper"] += 1
    return tally


def signature(offers: list[dict]) -> str:
    return "|".join(sorted(f"{offer['serviceDate']} {offer['start']} {offer['end']}" for offer in offers))


def earliest(offers: list[dict]) -> str | None:
    return min((offer["serviceDate"] for offer in offers), default=None)


def server_offers(reports: list[dict], variant: str | None = None) -> dict | None:
    """The booking offers 201 breakdown of the reports, pooled by weighting each report's p50 by its calls."""
    found = []
    for report in reports:
        breakdown = report.get("serverBreakdown")
        if breakdown is None:
            continue
        table = breakdown.get(variant) if variant is not None else breakdown
        if table and OFFERS in table:
            found.append(table[OFFERS])
    if not found:
        return None
    calls = sum(item["calls"] for item in found)
    def pooled(field: str, stat: str) -> float | None:
        values = [(item[field][stat], item["calls"]) for item in found if item.get(field) is not None]
        return None if not values else sum(value * weight for value, weight in values) / sum(weight for _, weight in values)
    return {"calls": calls, **{field: {"p50": pooled(field, "p50"), "p95": pooled(field, "p95")}
                                for field in ("elapsedMs", "queueMs", "routingMs", "calculationMs", "dbMs", "commitMs", "routingPairs", "fetched", "cpuMs")}}


def quality_lines(evidence: dict) -> list[str]:
    lines = ["## Offer quality against the uncapped threshold-1000 search, by fleet and schedule", "",
             "Both schedules of each cell pooled. Cost change is the variant's best offer against threshold 1000's on the same case; "
             "windows found is the first pass's distinct regular window count (what the threshold is compared with).", ""]
    for (fleet, profile), group_cells in group(cells(evidence, "quality"), "fleet", "profile").items():
        reports = [report for cell in group_cells for report in cell["reports"]]
        tally = quality_pairs(reports)
        lines += [f"### {fleet} technicians, {profile} schedule", "",
                  "| Variant | Answered | Priced vs 1000 | Cheaper / dearer | Mean / median change ($) | Same offers | Earlier / later first offer | "
                  "Refinement | Stopped by | Windows found p50 | searchComplete false | Search p50 ms | Engine / routing / commit p50 ms |",
                  "| ---: | ---: | ---: | --- | --- | ---: | --- | --- | --- | ---: | ---: | ---: | --- |"]
        for name in variant_order(set(tally)):
            s = tally[name]
            server = server_offers(reports, name)
            attributed = "-" if server is None else f"{fixed(server['calculationMs']['p50'])} / {fixed(server['routingMs']['p50'])} / {fixed(server['commitMs']['p50'])}"
            change = "(reference)" if name == REFERENCE else f"{dollars(mean(s['changes']))} / {dollars(median(s['changes']))}"
            lines.append(f"| {name} | {s['answered']} of {s['searches']} | {s['priced']} | {s['cheaper']} / {s['dearer']} | {change} | {s['same']} | "
                         f"{s['earlier']} / {s['later']} | {entries(s['refinements'])} | {entries(s['stops'])} | {fixed(median(s['windows']))} | {s['incomplete']} | "
                         f"{fixed(median(s['ms']))} | {attributed} |")
        windows = [value for s in tally.values() for value in s["windows"]]
        if windows:
            shares = ", ".join(f"{threshold}: {100 * sum(1 for value in windows if value <= threshold) / len(windows):.0f}%"
                               for threshold in (0, 1, 2, 3, 5, 10, 20))
            lines.append("")
            lines.append(f"Share of first passes with at most N windows (the refinement would be required at threshold N): {shares}; "
                         f"over {len(windows)} searches, p50 {median(windows):.0f}, {min(windows)} to {max(windows)}.")
        lines.append("")
    return lines


def entries(record: dict) -> str:
    return ", ".join(f"{key} {value}" for key, value in sorted(record.items())) or "none"


# Load: one desk per run, the repeats of a cell pooled.

def operation(report: dict, name: str) -> dict | None:
    return next((op for op in report["operations"] if op["operation"] == name), None)


def load_row(reports: list[dict]) -> dict:
    """One variant's figures pooled over its repeats: rates summed over time, percentiles averaged over repeats."""
    seconds = sum((parse(report["finishedAt"]) - parse(report["startedAt"])) for report in reports)
    searches = sum(report["booking"]["searches"] for report in reports)
    answered = sum(report["booking"]["searches"] - report["booking"]["failedSearches"] for report in reports)
    offers = [operation(report, "booking offers") for report in reports]
    confirms = [operation(report, "confirm") for report in reports]
    refinements: dict[str, int] = defaultdict(int)
    stops: dict[str, int] = defaultdict(int)
    for report in reports:
        for key, value in report["booking"]["refinements"].items():
            refinements[key] += value
        for key, value in report["booking"].get("refinementStops", {}).items():
            stops[key] += value
    server = server_offers(reports)
    cpu = [report["server"]["processCpu"]["mean"] for report in reports if report.get("server") and report["server"].get("processCpu")]
    heap = [report["server"]["heapUsedMaxBytes"] for report in reports if report.get("server") and report["server"].get("heapUsedMaxBytes")]
    pairs = [report["server"]["routingPairs"] for report in reports if report.get("server")]
    return {
        "runs": len(reports), "searches": searches, "answered": answered, "perMinute": None if seconds == 0 else searches / (seconds / 60),
        "p50": mean([op["ms"]["p50"] for op in offers if op and op["ms"]]), "p95": mean([op["ms"]["p95"] for op in offers if op and op["ms"]]),
        "p99": mean([op["ms"]["p99"] for op in offers if op and op["ms"]]),
        "busy": sum(op["retriedBusy"] for op in offers if op), "unavailable": sum(op["statuses"].get("503", 0) for op in offers if op),
        "confirmP50": mean([op["ms"]["p50"] for op in confirms if op and op["ms"]]), "confirmP95": mean([op["ms"]["p95"] for op in confirms if op and op["ms"]]),
        "holdsLost": sum(report["booking"]["holdUnavailable"] for report in reports), "confirmed": sum(report["booking"]["confirmed"] for report in reports),
        "incomplete": sum(report["booking"]["searchIncomplete"] for report in reports), "refinements": refinements, "stops": stops,
        "server": server, "cpu": mean(cpu), "heap": max(heap) if heap else None, "pairsPerSearch": None if not pairs or searches == 0 else sum(pairs) / searches,
    }


def parse(instant: str) -> float:
    from datetime import datetime
    return datetime.fromisoformat(instant.replace("Z", "+00:00")).timestamp()


def shed_share(row: dict) -> float | None:
    return None if row["answered"] == 0 else 100 * row["refinements"].get("SHED", 0) / row["answered"]


def load_lines(evidence: dict, part: str = "load", heading: str = "## One desk: latency, throughput and where the scheduler spent the time") -> list[str]:
    lines = [heading, "", "Repeats pooled: searches per minute over the repeats' total time, percentiles averaged over repeats. "
             "Server columns are the scheduler's request log for booking offers (p50): slot wait, routing, engine, database, commit.", ""]
    for (fleet, profile), group_cells in group(cells(evidence, part), "fleet", "profile").items():
        lines += [f"### {fleet} technicians, {profile} schedule", "",
                  "| Variant | Searches / min | Caller p50 / p95 / p99 ms | 503s | searchComplete false | Refinement | Stopped by | Confirm p50 / p95 ms | Holds lost | "
                  "Server p50: wait / routing / engine / db / commit ms | Pairs per search | CPU mean | Heap max GiB |",
                  "| ---: | ---: | --- | ---: | ---: | --- | --- | --- | ---: | --- | ---: | ---: | ---: |"]
        by_variant = group(group_cells, "variant")
        for (name,), variant_cells in sorted(by_variant.items(), key=lambda entry: variant_order({entry[0][0]}) and (entry[0][0] != REFERENCE, entry[0][0])):
            row = load_row([report for cell in variant_cells for report in cell["reports"]])
            lines.append(f"| {name} | {fixed(row['perMinute'], 1)} | {fixed(row['p50'])} / {fixed(row['p95'])} / {fixed(row['p99'])} | {row['unavailable']} | {row['incomplete']} | "
                         f"{entries(row['refinements'])} | {entries(row['stops'])} | {fixed(row['confirmP50'])} / {fixed(row['confirmP95'])} | {row['holdsLost']} | "
                         f"{server_cell(row['server'])} | {fixed(row['pairsPerSearch'])} | {fixed(None if row['cpu'] is None else row['cpu'] * 100)}% | "
                         f"{fixed(None if row['heap'] is None else row['heap'] / 1024 ** 3, 2)} |")
        lines.append("")
    return lines


def server_cell(server: dict | None) -> str:
    if server is None:
        return "-"
    return " / ".join(fixed(server[field]["p50"]) for field in ("queueMs", "routingMs", "calculationMs", "dbMs", "commitMs"))


# The price of quality: the cost gap to the reference beside the throughput and latency at one desk.

def price_lines(evidence: dict) -> list[str]:
    lines = ["## The price of quality: cost gap to threshold 1000 beside one desk's throughput", "",
             "The gap is the quality part's mean cost change against threshold 1000 (negative: cheaper than 1000); throughput and p50 are the load part's at one desk. "
             "Throughput kept is against 2:250, today's default.", ""]
    quality_by_cell = {key: quality_pairs([report for cell in group_cells for report in cell["reports"]])
                       for key, group_cells in group(cells(evidence, "quality"), "fleet", "profile").items()}
    for (fleet, profile), group_cells in group(cells(evidence, "load"), "fleet", "profile").items():
        tally = quality_by_cell.get((fleet, profile), {})
        rows = {name: load_row([report for cell in variant_cells for report in cell["reports"]]) for (name,), variant_cells in group(group_cells, "variant").items()}
        today = rows.get("2:250")
        lines += [f"### {fleet} technicians, {profile} schedule", "",
                  "| Variant | Cost gap to 1000, mean ($) | Cheaper / dearer | Searches / min | Throughput kept vs 2:250 | Caller p50 ms | Shed | Stopped by |",
                  "| ---: | ---: | --- | ---: | ---: | ---: | ---: | --- |"]
        for name in variant_order(set(rows)):
            row, quality = rows[name], tally.get(name)
            gap = "(reference)" if name == REFERENCE else "-" if quality is None else dollars(mean(quality["changes"]))
            direction = "-" if quality is None or name == REFERENCE else f"{quality['cheaper']} / {quality['dearer']}"
            kept = "-" if today is None or today["perMinute"] in (None, 0) or row["perMinute"] is None else f"{100 * row['perMinute'] / today['perMinute']:.0f}%"
            lines.append(f"| {name} | {gap} | {direction} | {fixed(row['perMinute'], 1)} | {kept} | {fixed(row['p50'])} | {fixed(shed_share(row))}% | {entries(row['stops'])} |")
        lines.append("")
    return lines


# Capacity: several clients at once, per layout; the clients of a cell summed.

def capacity_lines(evidence: dict) -> list[str]:
    lines = ["## Clients at once: booking pool layouts (replicas x slots, a thread a slot)", "",
             "Searches per minute summed over the clients; p50 / p95 averaged over the clients' own; shed is the share of answered searches whose "
             "optional refinement was given up; server columns from the request log of every replica (client 1 reads it).", ""]
    for (fleet, profile, variant), group_cells in group(cells(evidence, "capacity"), "fleet", "profile", "variant").items():
        lines += [f"### {fleet} technicians, {profile} schedule, variant {variant}", "",
                  "| Layout | Threads | Clients | Searches / min, all | Per client | Caller p50 / p95 ms | Waited on BUSY | 503s | Shed | Refinement | "
                  "Server p50: wait / routing / engine / db / commit ms | Fetched legs per search p50 | Request lines per replica (client 1) |",
                  "| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- | --- | ---: | --- |"]
        ordered = sorted(group_cells, key=lambda cell: (cell["layout"]["replicas"] * cell["layout"]["cpus"], cell["layout"]["replicas"], cell["clients"]))
        for cell in ordered:
            layout = cell["layout"]
            rows = [load_row([report]) for report in cell["reports"]]
            per_minute = [row["perMinute"] for row in rows if row["perMinute"] is not None]
            answered = sum(row["answered"] for row in rows)
            shed = sum(row["refinements"].get("SHED", 0) for row in rows)
            refinements: dict[str, int] = defaultdict(int)
            for row in rows:
                for key, value in row["refinements"].items():
                    refinements[key] += value
            server = server_offers(cell["reports"])
            fetched = "-" if server is None or server["fetched"]["p50"] is None else fixed(server["fetched"]["p50"])
            # The proxy's round-robin seen from the request log: how many of client 1's lines each replica wrote.
            shares = next((report["serverLog"] for report in cell["reports"] if report.get("serverLog")), None)
            per_replica = "-" if shares is None else " / ".join(str(share["apiLines"]) for share in shares)
            lines.append(f"| {layout['replicas']}x{layout['slots']} | {layout['replicas'] * layout['cpus']} | {cell['clients']} | {fixed(sum(per_minute), 1)} | "
                         f"{fixed(mean(per_minute), 1)} | {fixed(mean([row['p50'] for row in rows if row['p50'] is not None]))} / "
                         f"{fixed(mean([row['p95'] for row in rows if row['p95'] is not None]))} | {sum(row['busy'] for row in rows)} | "
                         f"{sum(row['unavailable'] for row in rows)} | {fixed(None if answered == 0 else 100 * shed / answered)}% | {entries(refinements)} | "
                         f"{server_cell(server)} | {fetched} | {per_replica} |")
        lines.append("")
    return lines


# Reserve: the commit phase against the reserve, and what the refinement gained from the time.

def reserve_lines(evidence: dict) -> list[str]:
    lines = ["## The publish reserve: commit time against the reserve, and what the refinement gained", "",
             "Load rows: the commit phase from the request log (p50 / p95 over the run's searches) against the reserve the pool ran with; a reserve is safe "
             "where commits stay inside it with no 503 and no searchComplete false from publishing. Quality rows: 2:3000 against 1000 on the same cases at that reserve.", ""]
    reserve_cells = cells(evidence, "reserve")
    default = [cell for cell in cells(evidence, "load")]
    for (fleet, profile), group_cells in group(reserve_cells, "fleet", "profile").items():
        lines += [f"### {fleet} technicians, {profile} schedule", "",
                  "| Reserve ms | Variant | Commit p50 / p95 ms | Caller p50 / p95 ms | Searches / min | 503s | searchComplete false | Stopped by | Refinement |",
                  "| ---: | ---: | --- | --- | ---: | ---: | ---: | --- | --- |"]
        load_rows = [cell for cell in group_cells if cell["variant"] is not None]
        baseline = [cell for cell in default if cell["fleet"] == fleet and cell["profile"] == profile and cell["variant"] in {cell2["variant"] for cell2 in load_rows}]
        for (reserve, name), variant_cells in sorted(group(load_rows + baseline, "reserveMs", "variant").items(), key=lambda entry: (-entry[0][0], entry[0][1])):
            row = load_row([report for cell in variant_cells for report in cell["reports"]])
            commit = "-" if row["server"] is None else f"{fixed(row['server']['commitMs']['p50'])} / {fixed(row['server']['commitMs']['p95'])}"
            lines.append(f"| {reserve} | {name} | {commit} | {fixed(row['p50'])} / {fixed(row['p95'])} | {fixed(row['perMinute'], 1)} | {row['unavailable']} | "
                         f"{row['incomplete']} | {entries(row['stops'])} | {entries(row['refinements'])} |")
        quality_cells = [cell for cell in group_cells if cell["variant"] is None]
        if quality_cells:
            lines += ["", "| Reserve ms | Variant | Priced vs 1000 | Cheaper / dearer | Mean change ($) | Stopped by |", "| ---: | ---: | ---: | --- | ---: | --- |"]
            for (reserve,), reserve_quality in sorted(group(quality_cells, "reserveMs").items(), key=lambda entry: -entry[0][0]):
                tally = quality_pairs([report for cell in reserve_quality for report in cell["reports"]])
                for name in variant_order(set(tally)):
                    if name == REFERENCE:
                        continue
                    s = tally[name]
                    lines.append(f"| {reserve} | {name} | {s['priced']} | {s['cheaper']} / {s['dearer']} | {dollars(mean(s['changes']))} | {entries(s['stops'])} |")
        lines.append("")
    return lines


PARTS = {"quality": quality_lines, "load": load_lines, "price": price_lines, "capacity": capacity_lines, "reserve": reserve_lines}


def summarize(evidence: dict, parts: list[str]) -> str:
    lines: list[str] = []
    for part in parts:
        lines += PARTS[part](evidence)
    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--part", choices=sorted(PARTS), action="append", help="one table set; every set without it")
    arguments = parser.parse_args(argv)
    evidence = read_evidence(arguments.evidence)
    sys.stdout.write(summarize(evidence, arguments.part or ["quality", "load", "price", "capacity", "reserve"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
