#!/usr/bin/env python3
"""Summarizes a VM performance run (infra/vm-perf/README.md) into summary.md beside it and one evidence JSON.

    python -I infra/vm-perf/summarize.py --run experiments/runs/vm-performance-2026-11 [--evidence <file>]

It reads only what the run wrote: each cell's cell.json and tool reports, each stage's stage.json and service logs
(saved on the VM before the next stage), the VM's samples.jsonl, and the GC logs. Every input is checked before use. A
figure that was not measured is written "not measured" and kept null in the evidence, never zero: a missing sample,
log or report never becomes a number. The bars below were fixed before the run (research/vm-performance-2026-11/plan.md).
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

# Bars written before the run (plan.md). Drag-and-drop bands are on a move's p95; a load step is past the VM's limit
# when any limit is crossed.
INSTANT_MS, ACCEPTABLE_MS, SLOW_MS = 100, 300, 1000
BOOKING_P95_LIMIT_MS = 5500
BUSY_SHARE_LIMIT = 0.01
# Binding-resource thresholds, also fixed before the run: a share of the cell's wall time.
THROTTLED_SHARE = 0.25      # a container's periods throttled by its CPU cap
HOST_PRESSURE_SHARE = 0.25  # time some task waited for a CPU (PSI cpu some)
HOST_BUSY_SHARE = 0.90      # host CPU busy of its capacity
STEAL_SHARE = 0.05          # time the hypervisor gave the vCPUs to someone else

NOT_MEASURED = "not measured"
NOT_APPLICABLE = "n/a"
# The moves a dispatcher makes; a session's steps and the sessions with nowhere to drop are parts of one, not bands.
MOVES = ("geometry", "insertions", "session")
SERVICES = ("scheduler-booking", "scheduler-daily", "routing-omaha", "scheduler-db", "scheduler-proxy")
SHORT = {"scheduler-booking": "booking", "scheduler-daily": "daily", "routing-omaha": "routing", "scheduler-db": "db", "scheduler-proxy": "proxy"}


class InputError(Exception):
    """An input the run wrote does not read as its writer promised."""


def parse_time(text: Any, where: str) -> float:
    if not isinstance(text, str):
        raise InputError(f"{where}: no timestamp")
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
    except ValueError as error:
        raise InputError(f"{where}: unreadable timestamp {text!r}") from error


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise InputError(f"{path}: {error}") from error


def percentile(values: list[float], fraction: float) -> float | None:
    """Nearest rank, as the kit computes it; None for no values."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def number(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) else None


# ------------------------------------------------------------------------------------------------------------- the cells
@dataclass
class Call:
    operation: str
    status: int | None
    error: str | None
    ms: float
    started_at: float
    headers_ms: float | None
    lag_ms: float | None


@dataclass
class Cell:
    stage: str
    name: str
    warmup: bool
    started: float
    finished: float
    exits: list[int | None]
    routes: list[dict[str, Any]] = field(default_factory=list)
    loads: list[dict[str, Any]] = field(default_factory=list)

    @property
    def seconds(self) -> float:
        return max(0.001, self.finished - self.started)

    def calls(self) -> list[Call]:
        found: list[Call] = []
        for report in self.routes:
            for record in report.get("records", []):
                operation, ms, started = record.get("operation"), number(record.get("ms")), number(record.get("startedAt"))
                if not isinstance(operation, str) or ms is None or started is None:
                    raise InputError(f"{self.stage}/{self.name}: a call record without operation, time or start")
                status = record.get("status")
                found.append(Call(operation, status if isinstance(status, int) else None, record.get("error"), ms, started / 1000,
                                  number(record.get("headersMs")), number(record.get("lagMs"))))
        return found


def read_cells(run: Path) -> list[Cell]:
    cells: list[Cell] = []
    for marker in sorted(run.glob("stages/*/cells/*/cell.json")):
        data = read_json(marker)
        where = str(marker)
        runs = data.get("runs")
        if not isinstance(runs, list):
            raise InputError(f"{where}: no runs")
        cell = Cell(stage=str(data.get("stage")), name=str(data.get("cell")), warmup=data.get("warmup") is True,
                    started=parse_time(data.get("startedAt"), where), finished=parse_time(data.get("finishedAt"), where),
                    exits=[item.get("exit") if isinstance(item.get("exit"), int) else None for item in runs])
        for report in sorted(marker.parent.glob("routes-*.json")):
            body = read_json(report)
            if not isinstance(body.get("operations"), list):
                raise InputError(f"{report}: no operations")
            cell.routes.append(body)
        for report in sorted(marker.parent.glob("load-*.json")):
            body = read_json(report)
            if not isinstance(body.get("operations"), list):
                raise InputError(f"{report}: no operations")
            cell.loads.append(body)
        cells.append(cell)
    return cells


def operation_of(reports: Iterable[dict[str, Any]], name: str) -> dict[str, Any] | None:
    for report in reports:
        for operation in report["operations"]:
            if operation.get("operation") == name:
                return operation
    return None


def status_share(operation: dict[str, Any] | None, status: str) -> float | None:
    if operation is None or not operation.get("calls"):
        return None
    return operation.get("statuses", {}).get(status, 0) / operation["calls"]


def band(p95: float | None) -> str:
    if p95 is None:
        return NOT_MEASURED
    if p95 < INSTANT_MS:
        return "instant"
    if p95 < ACCEPTABLE_MS:
        return "acceptable"
    if p95 <= SLOW_MS:
        return "noticeable"
    return "slow"


# -------------------------------------------------------------------------------------------------------- the server log
API_LINE = re.compile(r'api operation="(?P<operation>[^"]*)" status=(?P<status>\d+) elapsedMs=(?P<elapsed>\d+)(?P<rest>.*)$')
FIELD = re.compile(r"(\w+)=(\d+)")
SOLVING_ENDED = re.compile(r"^Solving ended: time spent \((?P<time>\d+)\),.* move evaluation speed \((?P<speed>\d+)/sec\)")


@dataclass
class ServerLine:
    at: float
    tenant: str
    operation: str
    status: int
    elapsed_ms: int
    fields: dict[str, int]


def read_server_log(path: Path) -> tuple[list[ServerLine], list[tuple[float, str, int]]]:
    """The request summary lines and the solver end lines (time, tenant, moves per second) of one ECS service log."""
    lines: list[ServerLine] = []
    solves: list[tuple[float, str, int]] = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        start = raw.find("{")
        if start < 0:
            continue
        try:
            record = json.loads(raw[start:])
        except ValueError:
            continue
        if not isinstance(record, dict) or not isinstance(record.get("message"), str):
            continue
        message: str = record["message"]
        if not (message.startswith("api operation=") or message.startswith("Solving ended: ")):
            continue
        at = parse_time(record.get("@timestamp"), f"{path}: a log line")
        tenant = record.get("tenant") if isinstance(record.get("tenant"), str) else ""
        ended = SOLVING_ENDED.match(message)
        if ended:
            solves.append((at, tenant, int(ended.group("speed"))))
            continue
        match = API_LINE.match(message)
        if match is None:
            raise InputError(f"{path}: unreadable request line {message!r}")
        lines.append(ServerLine(at, tenant, match.group("operation"), int(match.group("status")), int(match.group("elapsed")),
                                {name: int(value) for name, value in FIELD.findall(match.group("rest"))}))
    return lines, solves


@dataclass
class StageLogs:
    lines: dict[str, list[ServerLine]]
    solves: list[tuple[float, str, int]]
    watchdog: list[str]
    started: float | None
    containers: list[dict[str, Any]]


def read_stage(run: Path, stage: str) -> StageLogs:
    base = run / "stages" / stage / "vm-out" / stage
    lines: dict[str, list[ServerLine]] = {}
    solves: list[tuple[float, str, int]] = []
    for service in SERVICES:
        log = base / "logs" / f"{service}.log"
        if log.exists():
            found, ended = read_server_log(log)
            lines[service] = found
            solves.extend(ended)
    watchdog_file = base / "logs" / "watchdog.log"
    watchdog = [line for line in watchdog_file.read_text(encoding="utf-8").splitlines() if line.strip()] if watchdog_file.exists() else []
    stage_file = base / "stage.json"
    started, containers = None, []
    if stage_file.exists():
        data = read_json(stage_file)
        started = parse_time(data.get("startedAt"), str(stage_file))
        containers = [item for item in data.get("containers", []) if isinstance(item, dict)]
    return StageLogs(lines, solves, watchdog, started, containers)


def server_view(logs: StageLogs, cell: Cell, tenant: str | None, service: str, operation: str) -> dict[str, float | None] | None:
    lines = [line for line in logs.lines.get(service, []) if cell.started <= line.at <= cell.finished + 1
             and line.operation == operation and (tenant is None or line.tenant == tenant)]
    if not lines:
        return None
    pick = lambda name: [float(line.fields[name]) for line in lines if name in line.fields]
    return {"calls": float(len(lines)), "elapsedP50": percentile([float(line.elapsed_ms) for line in lines], 0.5),
            "queueP50": percentile(pick("queueMs"), 0.5), "routingP50": percentile(pick("routingMs"), 0.5),
            "cpuP50": percentile(pick("cpuMs"), 0.5), "queueP95": percentile(pick("queueMs"), 0.95)}


# ----------------------------------------------------------------------------------------------------------- the samples
@dataclass
class Sample:
    at: float
    raw: dict[str, Any]


def read_samples(path: Path) -> list[Sample]:
    samples: list[Sample] = []
    if not path.exists():
        return samples
    text_lines = path.read_text(encoding="utf-8").splitlines()
    for number_, line in enumerate(text_lines, 1):
        if not line.strip():
            continue
        try:
            raw = json.loads(line)
        except ValueError as error:
            # The last line can be cut by the restore; any other unreadable line is a defect of the sampler.
            if number_ == len(text_lines):
                continue
            raise InputError(f"{path}:{number_}: {error}") from error
        at = number(raw.get("at"))
        if at is None or not isinstance(raw.get("host"), dict) or not isinstance(raw.get("containers"), dict):
            raise InputError(f"{path}:{number_}: a sample without time, host or containers")
        samples.append(Sample(at, raw))
    return samples


def get(raw: Any, *keys: str) -> float | None:
    for key in keys:
        if not isinstance(raw, dict):
            return None
        raw = raw.get(key)
    return number(raw)


def delta(first: Sample, last: Sample, *keys: str) -> float | None:
    a, b = get(first.raw, *keys), get(last.raw, *keys)
    return None if a is None or b is None or b < a else b - a


def same_container(first: Sample, last: Sample, service: str) -> bool:
    a, b = first.raw["containers"].get(service, {}), last.raw["containers"].get(service, {})
    return isinstance(a, dict) and isinstance(b, dict) and a.get("id") is not None and a.get("id") == b.get("id")


def resources(samples: list[Sample], start: float, end: float) -> dict[str, Any] | None:
    """What the cell's window cost: per container cores used, share throttled, memory peak, OOM kills; host busy, steal,
    CPU and memory pressure, least available memory. Counters across a container restart are not compared."""
    inside = [sample for sample in samples if start <= sample.at <= end]
    if len(inside) < 2:
        return None
    first, last = inside[0], inside[-1]
    wall = last.at - first.at
    if wall <= 0:
        return None
    result: dict[str, Any] = {"seconds": wall, "containers": {}, "restarted": []}
    for service in SERVICES:
        if not same_container(first, last, service):
            if service in first.raw["containers"] and isinstance(first.raw["containers"][service], dict) and first.raw["containers"][service].get("id"):
                result["restarted"].append(service)
            continue
        usage = delta(first, last, "containers", service, "cpuStat", "usage_usec")
        throttled = delta(first, last, "containers", service, "cpuStat", "nr_throttled")
        periods = delta(first, last, "containers", service, "cpuStat", "nr_periods")
        oom = delta(first, last, "containers", service, "memoryEvents", "oom_kill")
        memory = [value for value in (get(sample.raw, "containers", service, "memoryCurrent") for sample in inside) if value is not None]
        result["containers"][service] = {
            "cores": None if usage is None else usage / 1e6 / wall,
            "throttledShare": None if throttled is None or not periods else throttled / periods,
            "memoryMaxBytes": max(memory) if memory else None,
            "oomKills": oom,
            "cpuSeconds": None if usage is None else usage / 1e6,
        }
    hz = get(last.raw, "host", "userHz")
    cpus = get(last.raw, "host", "cpus")
    parts = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
    ticks = {part: delta(first, last, "host", "cpu", part) for part in parts}
    if all(value is not None for value in ticks.values()):
        total = sum(value for value in ticks.values() if value is not None)
        busy = total - (ticks["idle"] or 0) - (ticks["iowait"] or 0)
        result["hostBusyShare"] = busy / total if total else None
        result["stealShare"] = (ticks["steal"] or 0) / total if total else None
    else:
        result["hostBusyShare"] = result["stealShare"] = None
    some_cpu = delta(first, last, "host", "pressure", "cpu", "some")
    some_memory = delta(first, last, "host", "pressure", "memory", "some")
    result["cpuPressureShare"] = None if some_cpu is None else some_cpu / 1e6 / wall
    result["memoryPressureShare"] = None if some_memory is None else some_memory / 1e6 / wall
    available = [value for value in (get(sample.raw, "host", "MemAvailableKb") for sample in inside) if value is not None]
    result["memAvailableMinMb"] = min(available) / 1024 if available else None
    swap = [(get(s.raw, "host", "SwapTotalKb"), get(s.raw, "host", "SwapFreeKb")) for s in inside]
    used = [(total - free) / 1024 for total, free in swap if total is not None and free is not None]
    result["swapUsedMaxMb"] = max(used) if used else None
    result["cpus"] = cpus
    result["userHz"] = hz
    return result


def binding(res: dict[str, Any] | None, busy_share: float | None) -> str:
    """What ran out first in a cell past the limit, by the thresholds fixed before the run; the order is the order of
    the checks: an out-of-memory kill, a container's own CPU cap, the host's cores, the hypervisor, then admission."""
    if res is None:
        return NOT_MEASURED
    for service, figures in res["containers"].items():
        if figures["oomKills"]:
            return f"memory ({SHORT[service]} killed)"
    capped = [(figures["throttledShare"], service) for service, figures in res["containers"].items() if figures["throttledShare"] is not None]
    worst = max(capped, default=None)
    if worst is not None and worst[0] >= THROTTLED_SHARE:
        return f"{SHORT[worst[1]]} CPU cap ({worst[0]:.0%} of periods throttled)"
    if (res["cpuPressureShare"] or 0) >= HOST_PRESSURE_SHARE or (res["hostBusyShare"] or 0) >= HOST_BUSY_SHARE:
        return f"host CPU ({(res['hostBusyShare'] or 0):.0%} busy, {(res['cpuPressureShare'] or 0):.0%} waiting)"
    if (res["stealShare"] or 0) >= STEAL_SHARE:
        return f"hypervisor steal ({res['stealShare']:.0%})"
    if busy_share is not None and busy_share > BUSY_SHARE_LIMIT:
        return "admission slots (busy answers with CPU to spare)"
    return "not identified"


# ------------------------------------------------------------------------------------------------------------- GC logs
GC_PAUSE = re.compile(r"\[gc\s*\]\s+GC\(\d+\)\s+Pause.*?\s(?P<ms>\d+(?:\.\d+)?)ms\s*$")
GC_USING = re.compile(r"\[gc(?:,init)?\s*\]\s+Using (?P<collector>[A-Za-z0-9 ]+)$")


def gc_view(folder: Path, name: str) -> dict[str, Any] | None:
    files = sorted(folder.glob(f"{name}-gc.log*"))
    if not files:
        return None
    collector, pauses = None, []
    for file in files:
        for line in file.read_text(encoding="utf-8", errors="replace").splitlines():
            using = GC_USING.search(line)
            if using and collector is None:
                collector = using.group("collector").strip()
            pause = GC_PAUSE.search(line)
            if pause:
                pauses.append(float(pause.group("ms")))
    return {"collector": collector, "pauses": len(pauses), "pauseTotalMs": sum(pauses) if pauses else 0.0 if collector else None,
            "pauseMaxMs": max(pauses) if pauses else None}


# ------------------------------------------------------------------------------------------------------------- markdown
def fmt(value: float | None, digits: int = 0, suffix: str = "") -> str:
    if value is None:
        return NOT_MEASURED
    return f"{value:,.{digits}f}{suffix}"


def share(value: float | None) -> str:
    return NOT_MEASURED if value is None else f"{value * 100:.1f}"


def table(headers: list[str], rows: list[list[str]], numeric: set[int]) -> list[str]:
    """An aligned markdown table (AGENTS.md): every cell padded to its column, numeric columns right-aligned."""
    widths = [max(len(headers[i]), *(len(row[i]) for row in rows), 3) for i in range(len(headers))]

    def line(cells: list[str]) -> str:
        return "| " + " | ".join(cell.rjust(widths[i]) if i in numeric else cell.ljust(widths[i]) for i, cell in enumerate(cells)) + " |"

    separator = "| " + " | ".join(("-" * (widths[i] - 1) + ":") if i in numeric else "-" * widths[i] for i in range(len(headers))) + " |"
    return [line(headers), separator, *(line(row) for row in rows)]


@dataclass
class Summary:
    lines: list[str] = field(default_factory=list)
    evidence: dict[str, Any] = field(default_factory=dict)

    def add(self, *lines: str) -> None:
        self.lines.extend(lines)


def tenant_of(run: Path) -> str | None:
    who = run / "vm" / "whoami.txt"
    if not who.exists():
        return None
    text = who.read_text(encoding="utf-8").strip()
    body = text.split(" ", 1)[1] if " " in text else ""
    try:
        parsed = json.loads(body)
    except ValueError:
        return None
    return parsed.get("tenantId") if isinstance(parsed, dict) and isinstance(parsed.get("tenantId"), str) else None


def api_path(run: Path) -> str:
    """How the workstation reached the API, from the runner's plan file (the latest if several); never assumed."""
    plans = sorted(run.glob("plan-*.json"), key=lambda path: path.stat().st_mtime)
    if not plans:
        return NOT_MEASURED
    try:
        plan = json.loads(plans[-1].read_text(encoding="utf-8-sig"))
    except ValueError as error:
        raise InputError(f"{plans[-1]}: {error}") from error
    value = plan.get("apiPath") if isinstance(plan, dict) else None
    return value if isinstance(value, str) and value else NOT_MEASURED


def summarize(run: Path) -> Summary:
    # In the order they ran, which is also the order of rising load within a series.
    cells = sorted((cell for cell in read_cells(run) if not cell.warmup), key=lambda cell: cell.started)
    samples = read_samples(run / "vm" / "samples.jsonl")
    tenant = tenant_of(run)
    stages = sorted({cell.stage for cell in cells}, key=lambda name: min(c.started for c in cells if c.stage == name))
    logs = {stage: read_stage(run, stage) for stage in stages}
    out = Summary()
    out.evidence = {"run": run.name, "tenant": tenant, "bars": {"instantMs": INSTANT_MS, "acceptableMs": ACCEPTABLE_MS, "slowMs": SLOW_MS,
                    "bookingP95LimitMs": BOOKING_P95_LIMIT_MS, "busyShareLimit": BUSY_SHARE_LIMIT, "throttledShare": THROTTLED_SHARE,
                    "hostPressureShare": HOST_PRESSURE_SHARE, "hostBusyShare": HOST_BUSY_SHARE, "stealShare": STEAL_SHARE}, "cells": [], "stages": {}}
    out.add("# VM performance run summary", "",
            f"Generated by infra/vm-perf/summarize.py from {run.name}. Every figure is the VM's own; a figure the run did not measure reads `{NOT_MEASURED}`.",
            "")
    live = run / "vm" / "live-caps.txt"
    host = run / "vm" / "host.txt"
    out.add("## The VM", "")
    if host.exists():
        out.add("The host as the run found it:", "", "```text", *host.read_text(encoding="utf-8").strip().splitlines(), "```", "")
    if live.exists():
        out.add("Its live settings (from deploy/vm/.env, no secrets):", "", "```text", *live.read_text(encoding="utf-8").strip().splitlines(), "```", "")
    restored = run / "vm" / "RESTORED"
    path = api_path(run)
    out.evidence["apiPath"] = path
    out.add(f"- **Restored to the live layout:** {restored.read_text(encoding='utf-8').strip() if restored.exists() else 'no restore recorded; check the VM'}.",
            f"- **How the calls reached the API:** {path}. Client-side times include that path; the server's own times (slot wait, routing, CPU) do not.", "")

    for cell in cells:
        res = resources(samples, cell.started, cell.finished)
        out.evidence["cells"].append({"stage": cell.stage, "cell": cell.name, "startedAt": cell.started, "finishedAt": cell.finished, "exits": cell.exits,
                                      "routes": [{k: v for k, v in report.items() if k != "records"} for report in cell.routes],
                                      "load": cell.loads, "resources": res})

    drive_cells = [cell for cell in cells if cell.routes and not cell.loads]
    if drive_cells:
        drive_section(out, drive_cells, logs, tenant, samples)
    capacity_cells = [cell for cell in cells if cell.loads]
    if capacity_cells:
        capacity_section(out, capacity_cells, logs, tenant, samples)
    stage_section(out, run, stages, logs, samples, cells)
    return out


def drive_section(out: Summary, cells: list[Cell], logs: dict[str, StageLogs], tenant: str | None, samples: list[Sample]) -> None:
    out.add("## Test 1: drive times", "",
            f"Bands on a move's p95, fixed before the run: instant under {INSTANT_MS} ms, acceptable under {ACCEPTABLE_MS} ms, noticeable to {SLOW_MS} ms, slow past it.", "")
    single = [cell for cell in cells if not cell.name.startswith(("paced-", "ladder-"))]
    if single:
        rows = []
        for cell in single:
            for report in cell.routes:
                for op in report["operations"]:
                    ms = op.get("ms") or {}
                    is_move = op["operation"] in MOVES
                    # A session is three calls, so it has no single time to headers.
                    headers = NOT_APPLICABLE if op["operation"].startswith("session") and "(" not in op["operation"] else fmt(number((op.get("headersMs") or {}).get("p95")))
                    rows.append([f"{cell.stage}/{cell.name}", op["operation"], str(op["calls"]), share(1 - (op.get("statuses", {}).get("200", 0) / op["calls"]) if op["calls"] else None),
                                 fmt(number(ms.get("p50"))), fmt(number(ms.get("p95"))), headers,
                                 band(number(ms.get("p95"))) if is_move else NOT_APPLICABLE])
        out.add("One dispatcher, one move at a time, by day and job placement (time from send to the full answer):", "",
                *table(["Cell", "Operation", "Calls", "Not 200 (%)", "p50 (ms)", "p95 (ms)", "Headers p95 (ms)", "Band"], rows, {2, 3, 4, 5, 6}), "")
        split = []
        for cell in single:
            for operation, path in (("geometry", "POST /api/v1/routes/geometry"), ("insertions", "POST /api/v1/routes/insertions")):
                client = operation_of(cell.routes, operation)
                server = server_view(logs[cell.stage], cell, tenant, "scheduler-booking", path) if cell.stage in logs else None
                if client is None:
                    continue
                client_p50 = number((client.get("ms") or {}).get("p50"))
                server_p50 = None if server is None else server["elapsedP50"]
                split.append([f"{cell.stage}/{cell.name}", operation, fmt(client_p50), fmt(server_p50),
                              fmt(None if server is None else server["queueP50"]), fmt(None if server is None else server["routingP50"]),
                              fmt(None if server is None else server["cpuP50"]),
                              fmt(None if client_p50 is None or server_p50 is None else client_p50 - server_p50)])
        if split:
            out.add("Where a call's time goes, at the median: the server's own time (its request log) split into waiting for a slot, routing and CPU, "
                    "and the rest (network, TLS, proxy):", "",
                    *table(["Cell", "Operation", "Client (ms)", "Server (ms)", "Slot wait (ms)", "Routing (ms)", "CPU (ms)", "Rest (ms)"], split, {2, 3, 4, 5, 6, 7}), "")
    paced = [cell for cell in cells if cell.name.startswith("paced-")]
    if paced:
        rows = []
        for cell in sorted(paced, key=lambda c: (c.stage, int(c.name.split("-")[1]))):
            session = operation_of(cell.routes, "session")
            open_, pick, drop = (operation_of(cell.routes, name) for name in ("session open (geometry)", "session pick (insertions)", "session drop (geometry)"))
            skipped = sum(int(report.get("skippedTicks", 0)) for report in cell.routes)
            p95 = lambda op: None if op is None else number((op.get("ms") or {}).get("p95"))
            lag = None if session is None else number((session.get("lagMs") or {}).get("p95"))
            res = resources(samples, cell.started, cell.finished)
            rows.append([cell.stage, cell.name.split("-")[1], fmt(p95(session)), fmt(p95(open_)), fmt(p95(pick)), fmt(p95(drop)), fmt(lag), str(skipped),
                         share(None if session is None else 1 - status_share(session, "200")),
                         fmt(None if res is None else res["containers"].get("scheduler-booking", {}).get("cores"), 2)])
        out.add("Dispatchers each moving every 10 s on the real day (open, pick, drop), on a fixed clock:", "",
                *table(["Stage", "Dispatchers", "Move p95 (ms)", "Open p95 (ms)", "Pick p95 (ms)", "Drop p95 (ms)", "Late start p95 (ms)", "Skipped",
                        "Not 200 (%)", "Booking cores"], rows, {1, 2, 3, 4, 5, 6, 7, 8, 9}), "")
    ladder = [cell for cell in cells if cell.name.startswith("ladder-")]
    if ladder:
        rows = []
        for cell in sorted(ladder, key=lambda c: (c.stage, c.name.split("-")[1], int(c.name.split("-")[2]))):
            kind = cell.name.split("-")[1]
            op = operation_of(cell.routes, kind)
            res = resources(samples, cell.started, cell.finished)
            busy = status_share(op, "429")
            rows.append([cell.stage, kind, cell.name.split("-")[2], fmt(None if op is None else number(op.get("okPerSecond")), 1),
                         fmt(None if op is None else number((op.get("ms") or {}).get("p95"))), share(busy), share(status_share(op, "503")),
                         binding(res, busy)])
        out.add("Saturation ladder: callers with no pause, to the peak rate and where refusals begin:", "",
                *table(["Stage", "Call", "Callers", "OK per s", "p95 (ms)", "429 (%)", "503 (%)", "What ran out"], rows, {2, 3, 4, 5, 6}), "")


def capacity_section(out: Summary, cells: list[Cell], logs: dict[str, StageLogs], tenant: str | None, samples: list[Sample]) -> None:
    out.add("## Test 2: booking and daily", "",
            f"A load step is past the VM's limit when booking offers p95 passes {BOOKING_P95_LIMIT_MS} ms, any call answers 503, more than "
            f"{BUSY_SHARE_LIMIT:.0%} answer 429, a queued daily waits longer than its own search, or a container restarts or is killed for memory.", "")
    rows, limits = [], []
    for cell in cells:
        for report in cell.loads:
            scenario = report.get("scenario")
            offers = operation_of([report], "booking offers")
            daily = operation_of([report], "daily proposal")
            queue_waits = (report.get("daily") or {}).get("queueWaitsMs") or []
            search_ms = None
            policy = (report.get("settings") or {}).get("policy") or {}
            if number(policy.get("costSearchSeconds")) is not None:
                search_ms = (number(policy.get("costSearchSeconds")) or 0) * 1000 + (number(policy.get("fairnessSearchSeconds")) or 0) * 1000
            res = resources(samples, cell.started, cell.finished)
            statuses: dict[str, int] = {}
            total = 0
            for op in report["operations"]:
                total += op["calls"]
                for key, value in op.get("statuses", {}).items():
                    statuses[key] = statuses.get(key, 0) + value
            busy = statuses.get("429", 0) / total if total else None
            # Any other refusal or server error (409 STALE is resent by the tool, so it is not one): a step whose calls
            # are refused measured nothing about capacity and must never read as within the limits.
            refused = sum(count for key, count in statuses.items() if not key.startswith("2") and key not in ("409", "429", "503", "none"))
            refused_share = refused / total if total else None
            offers_p95 = None if offers is None else number((offers.get("ms") or {}).get("p95"))
            wait_max = max(queue_waits) if queue_waits else None
            restarted = [] if res is None else res["restarted"]
            killed = [] if res is None else [s for s, f in res["containers"].items() if f["oomKills"]]
            reasons = []
            if offers_p95 is not None and offers_p95 > BOOKING_P95_LIMIT_MS:
                reasons.append("offers p95")
            if statuses.get("503", 0) or statuses.get("none", 0):
                reasons.append("503 or no answer")
            if busy is not None and busy > BUSY_SHARE_LIMIT:
                reasons.append("429")
            if refused_share is not None and refused_share > BUSY_SHARE_LIMIT:
                reasons.append("calls refused")
            if wait_max is not None and search_ms is not None and wait_max > search_ms:
                reasons.append("queue wait")
            if restarted or killed:
                reasons.append("restart")
            minutes = cell.seconds / 60
            per_minute = None if offers is None else offers["calls"] / minutes
            per_hour = None if daily is None else (daily.get("statuses", {}).get("201", 0)) / (cell.seconds / 3600)
            row = {"stage": cell.stage, "cell": cell.name, "scenario": scenario, "offersPerMinute": per_minute, "offersP95": offers_p95,
                   "proposalsPerHour": per_hour, "proposalP95": None if daily is None else number((daily.get("ms") or {}).get("p95")),
                   "queueWaitMaxMs": wait_max, "busyShare": busy, "refusedShare": refused_share, "unavailable": statuses.get("503", 0), "overLimit": reasons,
                   "binding": binding(res, busy) if reasons else None}
            limits.append(row)
            rows.append([cell.stage, cell.name, fmt(per_minute, 1), fmt(offers_p95), fmt(row["proposalsPerHour"], 0), fmt(row["proposalP95"]),
                         fmt(wait_max), share(busy), str(statuses.get("503", 0)), ", ".join(reasons) or "within"])
    out.evidence["capacity"] = limits
    out.add("Each load step against the limits (searches and proposals per unit of the step's wall time):", "",
            *table(["Stage", "Cell", "Searches per min", "Offers p95 (ms)", "Daily per h", "Daily p95 (ms)", "Queue wait max (ms)", "429 (%)", "503", "Limit"],
                   rows, {2, 3, 4, 5, 6, 7, 8}), "")
    over = [row for row in limits if row["overLimit"]]
    if over:
        out.add("What ran out first in each step past the limit (bars fixed before the run: a container throttled in "
                f"{THROTTLED_SHARE:.0%} of its periods, the host {HOST_BUSY_SHARE:.0%} busy or waiting {HOST_PRESSURE_SHARE:.0%} of the time, "
                f"steal over {STEAL_SHARE:.0%}):", "")
        for row in over:
            out.add(f"- **{row['stage']}/{row['cell']}:** {row['binding']} (crossed: {', '.join(row['overLimit'])}).")
        out.add("")
    # Unit costs: CPU seconds per call, from the container counters over cells that ran one kind of work.
    units = []
    for cell in cells:
        res = resources(samples, cell.started, cell.finished)
        if res is None:
            continue
        for report in cell.loads:
            if report.get("scenario") == "booking":
                offers = operation_of([report], "booking offers")
                booking = res["containers"].get("scheduler-booking", {}).get("cpuSeconds")
                if offers and offers["calls"] and booking is not None:
                    units.append([f"{cell.stage}/{cell.name}", "booking search", fmt(booking / offers["calls"], 3),
                                  fmt((res["containers"].get("routing-omaha", {}).get("cpuSeconds") or 0) / offers["calls"], 3)])
            if report.get("scenario") == "daily":
                daily = operation_of([report], "daily proposal")
                spent = res["containers"].get("scheduler-daily", {}).get("cpuSeconds")
                done = 0 if daily is None else daily.get("statuses", {}).get("201", 0)
                if done and spent is not None:
                    units.append([f"{cell.stage}/{cell.name}", "daily proposal", fmt(spent / done, 2),
                                  fmt((res["containers"].get("routing-omaha", {}).get("cpuSeconds") or 0) / done, 3)])
    if units:
        out.add("CPU seconds per answered call, from the containers' own counters over each step:", "",
                *table(["Cell", "Call", "Scheduler CPU (s)", "Routing CPU (s)"], units, {2, 3}), "")
    speeds = []
    for stage, stage_logs in logs.items():
        own = [speed for _, who, speed in stage_logs.solves if tenant is None or who == tenant]
        if own:
            speeds.append([stage, str(len(own)), fmt(percentile([float(s) for s in own], 0.5)), fmt(percentile([float(s) for s in own], 0.05))])
    if speeds:
        out.add("Solver speed on the VM (Timefold's move evaluation speed per phase, from the daily replica's log):", "",
                *table(["Stage", "Phases", "Moves per s p50", "Moves per s p5"], speeds, {1, 2, 3}), "")


def cap_text(nano: float | None) -> str:
    """A container's CPU cap: Docker's 0 is no cap at all, never a cap of zero."""
    if nano is None:
        return NOT_MEASURED
    return "uncapped" if nano == 0 else f"{nano / 1e9:.2f}"


def stage_section(out: Summary, run: Path, stages: list[str], logs: dict[str, StageLogs], samples: list[Sample], cells: list[Cell]) -> None:
    out.add("## Stages, collectors and recordings", "")
    rows = []
    for stage in stages:
        stage_logs = logs[stage]
        folder = run / "stages" / stage / "vm-profiles" / stage
        caps = ", ".join(f"{SHORT[str(c['service'])]} {cap_text(number(c.get('nanoCpus')))}"
                         for c in stage_logs.containers if c.get("service") in ("scheduler-booking", "scheduler-daily", "routing-omaha"))
        booking, daily = gc_view(folder, "booking"), gc_view(folder, "daily")
        jfr = sorted(path.name for path in folder.glob("*.jfr")) if folder.exists() else []
        solves = len(list((folder / "solves").glob("*.jfr"))) if (folder / "solves").exists() else 0
        rows.append([stage, caps or NOT_MEASURED,
                     NOT_MEASURED if booking is None else f"{booking['collector'] or 'unknown'} {fmt(booking['pauseTotalMs'])} ms",
                     NOT_MEASURED if daily is None else f"{daily['collector'] or 'unknown'} {fmt(daily['pauseTotalMs'])} ms",
                     str(len(jfr)), str(solves), str(len(stage_logs.watchdog))])
        out.evidence["stages"][stage] = {"containers": stage_logs.containers, "gc": {"booking": booking, "daily": daily}, "recordings": jfr,
                                         "solveRecordings": solves, "watchdog": stage_logs.watchdog}
    out.add("Each stage's CPU caps (vCPU), collector and total GC pause, flight recordings and watchdog lines:", "",
            *table(["Stage", "Caps (vCPU)", "Booking GC", "Daily GC", "Recordings", "Solve recordings", "Watchdog"], rows, {4, 5, 6}), "")
    out.add("Open a recording (`stages/<stage>/vm-profiles/<stage>/*.jfr`) in JDK Mission Control for the deep dive: Method Profiling for hot code, "
            "Garbage Collections for pauses, Lock Instances and Thread Dumps for waiting. Per-solve recordings (`solves/`) are summarized by "
            "infra/stack_profiles.py.", "")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--evidence", type=Path, default=None, help="default research/evidence/<run name>.json")
    args = parser.parse_args(argv)
    run: Path = args.run.resolve()
    if not (run / "stages").is_dir():
        print(f"{run} has no stages folder: nothing ran", file=sys.stderr)
        return 2
    try:
        summary = summarize(run)
    except InputError as error:
        print(f"Cannot summarize: {error}", file=sys.stderr)
        return 1
    (run / "summary.md").write_text("\n".join(summary.lines).rstrip("\n") + "\n", encoding="utf-8", newline="\n")
    repo = Path(__file__).resolve().parents[2]
    evidence = args.evidence or repo / "research" / "evidence" / f"{run.name}.json"
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(summary.evidence, indent=1, default=str) + "\n", encoding="utf-8", newline="\n")
    print(f"Wrote {run / 'summary.md'} and {evidence}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
