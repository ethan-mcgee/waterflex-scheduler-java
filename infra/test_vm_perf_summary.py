"""Tests for infra/vm-perf/summarize.py on a small synthetic run folder."""
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("vm_perf_summarize", Path(__file__).parent / "vm-perf" / "summarize.py")
assert _SPEC is not None and _SPEC.loader is not None
summarize = importlib.util.module_from_spec(_SPEC)
sys.modules["vm_perf_summarize"] = summarize
_SPEC.loader.exec_module(summarize)

T0 = datetime(2026, 11, 24, 8, 0, tzinfo=timezone.utc).timestamp()


def iso(offset: float) -> str:
    return datetime.fromtimestamp(T0 + offset, timezone.utc).isoformat().replace("+00:00", "Z")


def write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content if isinstance(content, str) else json.dumps(content), encoding="utf-8")


def cell(run: Path, stage: str, name: str, start: float, end: float, routes=None, load=None, warmup=False) -> None:
    folder = run / "stages" / stage / "cells" / name
    if routes is not None:
        write(folder / "routes-geometry-x.json", routes)
    if load is not None:
        write(folder / "load-booking-x.json", load)
    write(folder / "cell.json", {"stage": stage, "cell": name, "warmup": warmup, "startedAt": iso(start), "finishedAt": iso(end),
                                 "runs": [{"tool": "routes", "exit": 0}]})


def operation(name: str, calls: int, statuses: dict, p50: float, p95: float, ok_per_second: float = 1.0) -> dict:
    return {"operation": name, "calls": calls, "statuses": statuses, "errors": {}, "reasons": {}, "retriedBusy": 0,
            "ms": {"p50": p50, "p95": p95, "p99": p95, "max": p95, "mean": p50}, "headersMs": {"p50": p50 - 5, "p95": p95 - 5, "p99": p95, "max": p95},
            "bytes": None, "lagMs": None, "okPerSecond": ok_per_second}


def sample(at: float, booking_usage: int, periods: int, throttled: int, booking_id: str = "b1", steal: int = 0) -> dict:
    container = lambda cid, usage, n, t: {"id": cid, "cpuMax": "100000 100000", "cpuStat": {"usage_usec": usage, "nr_periods": n, "nr_throttled": t, "throttled_usec": t * 10},
                                          "memoryCurrent": 500_000_000, "memoryPeak": 600_000_000, "memoryEvents": {"oom": 0, "oom_kill": 0}}
    return {"at": T0 + at, "host": {"cpu": {"user": 1000 + at * 50, "nice": 0, "system": 100, "idle": 5000 + at * 150, "iowait": 0, "irq": 0, "softirq": 0, "steal": steal},
                                    "userHz": 100, "cpus": 2, "loadavg": "1 1 1", "MemAvailableKb": 4_000_000, "SwapTotalKb": 4_000_000, "SwapFreeKb": 4_000_000,
                                    "freeGb": 10, "pressure": {"cpu": {"some": int(at * 50_000)}, "memory": {"some": 0, "full": 0}, "io": {"some": 0, "full": 0}}},
            "containers": {"scheduler-booking": container(booking_id, booking_usage, periods, throttled), "scheduler-daily": container("d1", 0, 0, 0),
                           "routing-omaha": container("r1", 1_000_000, 0, 0), "scheduler-db": {"error": "not running"}, "scheduler-proxy": container("p1", 0, 0, 0)}}


def ecs(offset: float, tenant: str, message: str) -> str:
    return json.dumps({"@timestamp": iso(offset), "log.level": "INFO", "message": message, "tenant": tenant, "requestId": "r"})


class SummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.run = Path(self.temp.name) / "vm-performance-test"
        run = self.run
        write(run / "vm" / "whoami.txt", '200 {"tenantId":"loadtest"}')
        write(run / "vm" / "RESTORED", "2026-11-24T16:00:00Z")
        # Test 1: one quick geometry cell, and a ladder step refused with 429s.
        records = [{"operation": "geometry", "status": 200, "error": None, "ms": 60, "startedAt": (T0 + 10) * 1000, "headersMs": 55, "lagMs": None}]
        cell(run, "drive-live", "warmup", 0, 5, routes={"operations": [operation("geometry", 5, {"200": 5}, 900, 900)], "records": []}, warmup=True)
        cell(run, "drive-live", "geometry-real", 5, 65, routes={"operations": [operation("geometry", 200, {"200": 200}, 60, 80)], "records": records})
        cell(run, "drive-live", "ladder-geometry-8", 65, 125, routes={"operations": [operation("geometry", 100, {"200": 50, "429": 50}, 400, 2000, 0.8)], "records": []})
        # Test 2: a booking step past the offers bar while the booking container is throttled, and one with no samples at all.
        load = {"scenario": "booking", "settings": {"policy": {"costSearchSeconds": 10, "fairnessSearchSeconds": 5}}, "daily": None,
                "operations": [operation("booking offers", 60, {"201": 60}, 3000, 6000)]}
        cell(run, "cap-booking", "booking-8x10t", 200, 260, load=load)
        cell(run, "cap-booking", "booking-16x10t", 5000, 5060, load={**load, "operations": [operation("booking offers", 10, {"201": 9, "503": 1}, 3000, 4000)]})
        # A daily step whose every proposal was refused (the days did not route): it measured nothing and is never "within".
        cell(run, "cap-daily", "daily-sync-5t", 6000, 6060, load={"scenario": "daily", "settings": {"policy": {"costSearchSeconds": 10, "fairnessSearchSeconds": 5}},
                                                                 "daily": {"queueWaitsMs": []}, "operations": [operation("daily proposal", 3, {"422": 3}, 500, 544)]})
        # Light CPU through the ladder step (65 to 125 s), the booking container throttled through the booking step (200 to 260 s).
        samples = [sample(0, 0, 0, 0), sample(70, 5_000_000, 700, 7), sample(120, 9_000_000, 1200, 12), sample(130, 10_000_000, 1300, 13),
                   sample(200, 20_000_000, 2000, 20), sample(260, 80_000_000, 2600, 400)]
        write(run / "vm" / "samples.jsonl", "\n".join(json.dumps(item) for item in samples) + "\n" + '{"at": 1, "host"')
        # The server's own log: one line of this tenant, one of another tenant inside the same seconds.
        write(run / "stages" / "drive-live" / "vm-out" / "drive-live" / "logs" / "scheduler-booking.log", "\n".join([
            ecs(10, "loadtest", 'api operation="POST /api/v1/routes/geometry" status=200 elapsedMs=40 queueMs=2 routingMs=30 calculationMs=0 dbMs=1 dbCalls=1 routingPairs=0 cacheHits=0 fetched=0 cpuMs=8'),
            ecs(11, "waterflex-dev", 'api operation="POST /api/v1/routes/geometry" status=200 elapsedMs=900 queueMs=800 routingMs=30 calculationMs=0 dbMs=1 dbCalls=1 routingPairs=0 cacheHits=0 fetched=0'),
            "not json at all"]) + "\n")
        write(run / "stages" / "drive-live" / "vm-out" / "drive-live" / "stage.json",
              {"stage": "drive-live", "startedAt": iso(0), "containers": [{"service": "scheduler-booking", "nanoCpus": 1_000_000_000}, {"service": "routing-omaha", "nanoCpus": 0}]})
        write(run / "stages" / "drive-live" / "vm-profiles" / "drive-live" / "booking-gc.log",
              "[0.010s][info][gc] Using Serial\n[5.000s][info][gc] GC(0) Pause Young (Allocation Failure) 20M->5M(64M) 3.500ms\n"
              "[9.000s][info][gc] GC(1) Pause Full (Allocation Failure) 60M->30M(64M) 41.250ms\n")
        write(run / "stages" / "drive-live" / "vm-profiles" / "drive-live" / "booking.jfr", "jfr")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def summary(self):
        return summarize.summarize(self.run)

    def test_bands_split_and_tenant_filter(self) -> None:
        text = "\n".join(self.summary().lines)
        row = next(line for line in text.splitlines() if "drive-live/geometry-real" in line and "| instant" in line)
        self.assertIn("80", row)
        lines = text.splitlines()
        header = next(i for i, line in enumerate(lines) if line.startswith("| Cell") and "Client (ms)" in line)
        split = lines[header + 2]
        cells = [item.strip() for item in split.strip("|").split("|")]
        self.assertEqual(cells[2:8], ["60", "40", "2", "30", "8", "20"], "the other tenant's 900 ms line is left out")
        self.assertNotIn("drive-live/warmup", text, "warm-up cells are not summarized")

    def test_limits_and_what_ran_out(self) -> None:
        out = self.summary()
        rows = {row["cell"]: row for row in out.evidence["capacity"]}
        self.assertEqual(rows["booking-8x10t"]["overLimit"], ["offers p95"])
        self.assertTrue(rows["booking-8x10t"]["binding"].startswith("booking CPU cap"), rows["booking-8x10t"]["binding"])
        self.assertEqual(rows["booking-16x10t"]["overLimit"], ["503 or no answer"])
        self.assertEqual(rows["booking-16x10t"]["binding"], summarize.NOT_MEASURED, "no samples in the window: never guessed")
        self.assertEqual(rows["daily-sync-5t"]["overLimit"], ["calls refused"], "a step of refused calls never reads as within the limits")

    def test_ladder_refusals_are_admission_when_cpu_is_spare(self) -> None:
        text = "\n".join(self.summary().lines)
        row = next(line for line in text.splitlines() if re.match(r"^\| drive-live +\| geometry +\| +8 \|", line))
        self.assertIn("50.0", row)
        self.assertIn("admission slots", row)

    def test_collector_caps_and_recordings(self) -> None:
        out = self.summary()
        gc = out.evidence["stages"]["drive-live"]["gc"]["booking"]
        self.assertEqual(gc["collector"], "Serial")
        self.assertEqual(gc["pauses"], 2)
        self.assertAlmostEqual(gc["pauseTotalMs"], 44.75)
        self.assertIsNone(out.evidence["stages"]["drive-live"]["gc"]["daily"], "no daily GC log is not a zero pause")
        text = "\n".join(out.lines)
        self.assertIn("booking 1.00, routing uncapped", text)

    def test_start_hangs_are_counted_never_assumed(self) -> None:
        stage_file = self.run / "stages" / "drive-live" / "vm-out" / "drive-live" / "stage.json"
        self.assertIsNone(self.summary().evidence["stages"]["drive-live"]["startHangs"], "a stage.json without the count is not zero hangs")
        data = json.loads(stage_file.read_text(encoding="utf-8"))
        write(stage_file, {**data, "startHangs": 1})
        out = self.summary()
        self.assertEqual(out.evidence["stages"]["drive-live"]["startHangs"], 1)
        row = next(line for line in out.lines if line.startswith("| drive-live ") and "Serial" in line)
        self.assertTrue(row.rstrip(" |").endswith("1"), row)
        for bad in (True, -1, "1"):
            write(stage_file, {**data, "startHangs": bad})
            with self.assertRaises(summarize.InputError, msg=repr(bad)):
                self.summary()

    def test_tables_are_aligned_and_missing_values_named(self) -> None:
        lines = self.summary().lines
        for start, line in enumerate(lines):
            if line.startswith("| ") and (start == 0 or not lines[start - 1].startswith("|")):
                block = [item for item in lines[start:] if item.startswith("|")]
                block = block[: next((i for i, item in enumerate(block) if not item.startswith("|")), len(block))]
                end = start
                while end < len(lines) and lines[end].startswith("|"):
                    end += 1
                widths = {len(item) for item in lines[start:end]}
                self.assertEqual(len(widths), 1, f"table at line {start} is not aligned")
        self.assertNotIn("| - |", "\n".join(lines))

    def test_a_restart_inside_a_step_is_seen(self) -> None:
        samples = [summarize.Sample(T0 + at, sample(at, 0, 0, 0, booking_id=cid)) for at, cid in ((0, "b1"), (60, "b2"))]
        res = summarize.resources(samples, T0 - 1, T0 + 61)
        self.assertEqual(res["restarted"], ["scheduler-booking"])
        self.assertNotIn("scheduler-booking", res["containers"], "counters across a restart are not compared")

    def test_a_corrupt_sample_line_is_refused_but_a_cut_last_line_is_not(self) -> None:
        path = self.run / "vm" / "samples.jsonl"
        self.assertEqual(len(summarize.read_samples(path)), 6)
        write(path, '{"broken"\n' + json.dumps(sample(0, 0, 0, 0)) + "\n")
        with self.assertRaises(summarize.InputError):
            summarize.read_samples(path)
        self.assertEqual(summarize.main(["--run", str(self.run), "--evidence", str(self.run / "evidence.json")]), 1)

    def test_the_api_path_is_read_from_the_plan_never_assumed(self) -> None:
        self.assertEqual(summarize.api_path(self.run), summarize.NOT_MEASURED, "no plan file: not measured")
        write(self.run / "plan-Smoke.json", "﻿" + json.dumps({"apiPath": "SSH tunnel to 192.168.20.171:443"}))
        self.assertEqual(summarize.api_path(self.run), "SSH tunnel to 192.168.20.171:443", "PowerShell's byte order mark is read")
        write(self.run / "plan-Smoke.json", "{broken")
        with self.assertRaises(summarize.InputError):
            summarize.api_path(self.run)

    def test_main_writes_summary_and_evidence(self) -> None:
        evidence = self.run / "evidence.json"
        self.assertEqual(summarize.main(["--run", str(self.run), "--evidence", str(evidence)]), 0)
        self.assertTrue((self.run / "summary.md").read_text(encoding="utf-8").startswith("# VM performance run summary\n"))
        self.assertEqual(json.loads(evidence.read_text(encoding="utf-8"))["tenant"], "loadtest")
        self.assertEqual(summarize.main(["--run", str(self.run / "nothing")]), 2)


if __name__ == "__main__":
    unittest.main()
