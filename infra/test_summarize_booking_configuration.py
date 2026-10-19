import gzip
import json
import tempfile
import unittest
from pathlib import Path

import summarize_booking_configuration as summary


def offer(date, hour):
    return {"serviceDate": date, "start": f"{date}T{hour}:00:00Z", "end": f"{date}T{hour + 2}:00:00Z"}


def searched(variant, offers, added, refinement="COMPLETE", stop=None, windows=7, ms=400, status=201):
    threshold, _, cap = variant.partition(":")
    return {"variant": variant, "threshold": int(threshold), "refinementMs": None if cap == "" else int(cap), "requestId": f"r-{variant}", "status": status,
            "ms": ms if status == 201 else None, "refinement": refinement if status == 201 else None, "refinementStop": stop, "regularWindowsFound": windows if status == 201 else None,
            "searchComplete": True if status == 201 else None, "incompleteDates": 0 if status == 201 else None, "offers": offers,
            "priced": None if added is None or status != 201 else {"beforeCostCents": 1000, "afterCostCents": 1000 + added, "addedCostCents": added, "reassignedVisits": 0, "reorderedDays": 0},
            "pricingFailure": None}


def breakdown(elapsed, queue=0, routing=50, engine=300, db=10, commit=40, calls=10):
    stat = lambda value: {"p50": value, "p95": value * 2, "mean": value}
    return {summary.OFFERS: {"calls": calls, "elapsedMs": stat(elapsed), "attributed": calls, "queueMs": stat(queue), "routingMs": stat(routing),
                             "calculationMs": stat(engine), "dbMs": stat(db), "dbCalls": stat(20), "routingPairs": stat(500), "cacheHits": stat(300),
                             "fetched": stat(200), "commitMs": None if commit is None else stat(commit), "cpuMs": stat(engine)}}


def quality_report(cases, variants):
    return {"label": "quality", "settings": {"variants": [{"name": name} for name in variants]}, "cases": cases,
            "serverBreakdown": {"2:250": breakdown(350, engine=250), "2:3000": breakdown(1800, engine=1700), "1000": breakdown(2200, engine=2100)},
            "contractBreaches": []}


def load_report(variant, searches, p50, refinements, stops=None, confirm=20, busy=0, unavailable=0, seconds=60, queue=0, commit=40, holds_lost=0):
    return {"label": f"load {variant}", "startedAt": "2026-10-20T00:00:00.000Z", "finishedAt": f"2026-10-20T00:0{seconds // 60}:{seconds % 60:02d}.000Z",
            "operations": [{"operation": "booking offers", "calls": searches, "statuses": {"201": searches - unavailable, **({"503": unavailable} if unavailable else {})},
                            "errors": {}, "reasons": {}, "retriedBusy": busy, "ms": {"p50": p50, "p95": p50 * 2, "p99": p50 * 3, "max": p50 * 4, "mean": p50}},
                           {"operation": "confirm", "calls": 5, "statuses": {"200": 5}, "errors": {}, "reasons": {}, "retriedBusy": 0,
                            "ms": {"p50": confirm, "p95": confirm * 2, "p99": confirm * 3, "max": confirm * 3, "mean": confirm}}],
            "booking": {"searches": searches, "searchIncomplete": 0, "offers": 4 * searches, "noOffers": 0, "selected": 5, "holdUnavailable": holds_lost, "confirmed": 5,
                        "hostWriteConflicts": 0, "released": 0, "failedSearches": unavailable, "stale": 0, "refinements": refinements, "refinementStops": stops or {},
                        "windowsFound": {"7": searches}, "byHorizon": {}},
            "server": {"operations": [], "admissionRejections": 0, "routingPairs": 500 * searches, "routingLegRequests": 10, "routingIdentityRequests": 1,
                       "processCpu": {"mean": 0.5, "max": 0.9}, "heapUsedMaxBytes": 1024 ** 3},
            "serverBreakdown": breakdown(p50, queue=queue, commit=commit, calls=searches), "containers": None, "contractBreaches": [], "hostAnomalies": []}


def cell(part, fleet, profile, reports, variant=None, schedule=None, repeat=None, clients=1, layout=None, reserve=1000):
    return {"part": part, "fleet": fleet, "profile": profile, "variant": variant, "schedule": schedule, "repeat": repeat, "clients": clients,
            "layout": layout or {"replicas": 1, "slots": 3, "cpus": 3}, "reserveMs": reserve, "reports": reports}


class SummaryTests(unittest.TestCase):
    def evidence(self):
        variants = ["1000", "2:250", "2:3000"]
        cases = [
            {"index": 1, "firstDate": "2026-10-20", "dates": 9, "order": variants, "searched": [
                searched("1000", [offer("2026-10-21", 9)], 2300, ms=2200, windows=6),
                searched("2:250", [offer("2026-10-22", 9)], 2500, "SHORTENED", "CAP", ms=350),
                searched("2:3000", [offer("2026-10-21", 9)], 2300, "SHORTENED", "DEADLINE", ms=1800, windows=8)]},
            {"index": 2, "firstDate": "2026-10-20", "dates": 9, "order": variants, "searched": [
                searched("1000", [offer("2026-10-21", 9)], 2400, ms=2100, windows=1),
                searched("2:250", [offer("2026-10-21", 9)], 2400, "SHORTENED", "CAP", ms=360, windows=1),
                searched("2:3000", [], None, status=503)]},
        ]
        return {"cells": [
            cell("quality", 5, "half", [quality_report(cases, variants)], schedule=1),
            cell("load", 5, "half", [load_report("2:250", 120, 350, {"SHORTENED": 120}, {"CAP": 120}), load_report("2:250", 118, 370, {"SHORTENED": 118}, {"CAP": 118})], variant="2:250", repeat=1),
            cell("load", 5, "half", [load_report("1000", 30, 2000, {"COMPLETE": 30}, commit=60)], variant="1000", repeat=1),
            cell("capacity", 5, "half", [load_report("2:250", 100, 200, {"SHED": 90, "SHORTENED": 10}, {"CAP": 10}, busy=3, queue=150),
                                         load_report("2:250", 98, 210, {"SHED": 88, "SHORTENED": 10}, {"CAP": 10})],
                 variant="2:250", repeat=1, clients=2, layout={"replicas": 1, "slots": 2, "cpus": 2}),
            cell("capacity", 5, "half", [{**load_report("2:250", 60, 400, {"SHORTENED": 60}, {"CAP": 60}),
                                          "serverLog": [{"container": "booking-1", "apiLines": 71}, {"container": "booking-2", "apiLines": 69}]},
                                         load_report("2:250", 58, 410, {"SHORTENED": 58}, {"CAP": 58})],
                 variant="2:250", repeat=1, clients=2, layout={"replicas": 2, "slots": 1, "cpus": 1}),
            cell("reserve", 5, "half", [load_report("1000", 36, 1700, {"COMPLETE": 36}, commit=55, unavailable=2)], variant="1000", repeat=1, reserve=500),
            cell("reserve", 5, "half", [quality_report(cases, variants)], schedule=1, reserve=500),
        ]}

    def test_quality_pairs_each_variant_with_the_reference_on_the_same_case(self):
        tally = summary.quality_pairs(self.evidence()["cells"][0]["reports"])
        capped = tally["2:250"]
        self.assertEqual((capped["compared"], capped["priced"], capped["cheaper"], capped["dearer"]), (2, 2, 0, 1))
        self.assertEqual(capped["changes"], [200, 0])
        self.assertEqual((capped["later"], capped["earlier"], capped["same"]), (1, 0, 1))
        self.assertEqual(dict(capped["stops"]), {"CAP": 2})
        self.assertEqual(sorted(capped["windows"]), [1, 7])
        long = tally["2:3000"]
        self.assertEqual((long["searches"], long["answered"], long["compared"]), (2, 1, 1), "the failed search is not compared")
        self.assertEqual(long["changes"], [0])
        self.assertEqual(tally["1000"]["compared"], 0, "the reference is compared with nothing")

    def test_quality_table_lists_variants_with_the_reference_first_and_the_window_shares(self):
        lines = summary.quality_lines(self.evidence())
        rows = [line for line in lines if line.startswith("| 1000 |") or line.startswith("| 2:")]
        self.assertEqual([row.split(" | ")[0] for row in rows], ["| 1000", "| 2:250", "| 2:3000"])
        self.assertIn("| 2:250 | 2 of 2 | 2 | 0 / 1 | +1.00 / +1.00 | 1 | 0 / 1 | SHORTENED 2 | CAP 2 | 4 | 0 | 355 | 250 / 50 / 40 |", rows[1])
        shares = next(line for line in lines if line.startswith("Share of first passes"))
        self.assertIn("0: 0%, 1: 40%, 2: 40%", shares, "two of five reported counts are at most 1")

    def test_load_rows_pool_repeats_and_read_the_server_breakdown(self):
        row = summary.load_row(self.evidence()["cells"][1]["reports"])
        self.assertEqual(row["searches"], 238)
        self.assertAlmostEqual(row["perMinute"], 119.0)
        self.assertEqual((row["p50"], row["confirmP50"]), (360, 20))
        self.assertEqual(row["server"]["calculationMs"]["p50"], 300)
        self.assertAlmostEqual(row["pairsPerSearch"], 500)
        lines = summary.load_lines(self.evidence())
        self.assertIn("| 2:250 | 119.0 | 360 / 720 / 1080 | 0 | 0 | SHORTENED 238 | CAP 238 | 20 / 40 | 0 | 0 / 50 / 300 / 10 / 40 | 500 | 50% | 1.00 |", lines)

    def test_price_table_puts_the_cost_gap_beside_the_throughput(self):
        lines = summary.price_lines(self.evidence())
        self.assertIn("| 2:250 | +1.00 | 0 / 1 | 119.0 | 100% | 360 | 0% | CAP 238 |", lines)
        self.assertIn("| 1000 | (reference) | - | 30.0 | 25% | 2000 | 0% | none |", lines)

    def test_capacity_rows_sum_the_clients_and_order_layouts_by_threads(self):
        lines = summary.capacity_lines(self.evidence())
        rows = [line for line in lines if line.startswith("| 1x2") or line.startswith("| 2x1")]
        self.assertEqual(rows[0].split(" | ")[:5], ["| 1x2", "2", "2", "198.0", "99.0"])
        # The server p50 is pooled by weighting each client's p50 by its calls: (150 x 100 + 0 x 98) / 198.
        self.assertIn("| 3 | 0 | 90% | SHED 178, SHORTENED 20 | 76 / 50 / 300 / 10 / 40 | 200 |", rows[0])
        self.assertEqual(rows[1].split(" | ")[:4], ["| 2x1", "2", "2", "118.0"])
        self.assertTrue(rows[0].endswith("| 200 | - |"), rows[0])
        self.assertTrue(rows[1].endswith("| 71 / 69 |"), "client 1's request lines per replica")

    def test_reserve_rows_show_commit_time_against_each_reserve_with_the_default_beside(self):
        lines = summary.reserve_lines(self.evidence())
        self.assertIn("| 1000 | 1000 | 60 / 120 | 2000 / 4000 | 30.0 | 0 | 0 | none | COMPLETE 30 |", lines)
        self.assertIn("| 500 | 1000 | 55 / 110 | 1700 / 3400 | 36.0 | 2 | 0 | none | COMPLETE 36 |", lines)
        self.assertIn("| 500 | 2:3000 | 1 | 0 / 0 | +0.00 | DEADLINE 1 |", lines)

    def test_the_whole_summary_renders_every_part(self):
        text = summary.summarize(self.evidence(), ["quality", "load", "price", "capacity", "reserve"])
        for heading in ("## Offer quality", "## One desk", "## The price of quality", "## Clients at once", "## The publish reserve"):
            self.assertIn(heading, text)

    def test_groups_order_fleets_by_size_and_schedules_from_empty_to_full(self):
        items = [{"fleet": fleet, "profile": profile} for fleet in (30, 5, 10) for profile in ("full", "empty", "half", "light")]
        keys = list(summary.group(items, "fleet", "profile"))
        self.assertEqual(keys[:4], [(5, "empty"), (5, "light"), (5, "half"), (5, "full")])
        self.assertEqual([key[0] for key in keys[::4]], [5, 10, 30])

    def test_gzipped_evidence_is_read_as_the_plain_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "study.json.gz"
            with gzip.open(path, "wt", encoding="utf-8") as handle:
                json.dump(self.evidence(), handle)
            self.assertEqual(len(summary.read_evidence(path)["cells"]), len(self.evidence()["cells"]))

    def test_evidence_without_cells_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "other-study.json"
            path.write_text(json.dumps({"study": "window-threshold", "runs": []}), encoding="utf-8")
            with self.assertRaises(ValueError):
                summary.read_evidence(path)


if __name__ == "__main__":
    unittest.main()
