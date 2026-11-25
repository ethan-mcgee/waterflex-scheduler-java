#!/usr/bin/env python3
"""Builds the VM performance test's pool of routable points from imported backtest days.

    python -I infra/vm-perf/build_points.py --out <run>/points.json [--glob '<api-request.json glob>'] [--box w,s,e,n]

The pool is every distinct appointment location of the imported days' API requests: real job addresses the import
already routed (it holds out the ones no road reaches), so a generated day routes the way the dealer's days do. Points
outside the map's box are left out. The file holds customer locations: it is written under the run folder
(experiments/runs, never committed) and sent only to the dealer's own scheduler.
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path

DEFAULT_GLOB = "experiments/runs/backtest-paso-robles-2026-11*/variants/*/days/*/api-request.json"
# The map's clip box (infra/map-import/regions/central-coast-ca.json): west, south, east, north.
DEFAULT_BOX = (-121.5, 34.3, -119.4, 36.3)


def collect(files: list[Path], box: tuple[float, float, float, float]) -> list[dict[str, float]]:
    """The distinct located appointment points of the files inside the box, sorted; an unreadable file is an error."""
    west, south, east, north = box
    seen: set[tuple[float, float]] = set()
    for file in files:
        try:
            request = json.loads(file.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise ValueError(f"{file}: {error}") from error
        snapshot = request.get("snapshot") if isinstance(request, dict) else None
        appointments = snapshot.get("appointments") if isinstance(snapshot, dict) else None
        if not isinstance(appointments, list):
            raise ValueError(f"{file}: no snapshot.appointments")
        for appointment in appointments:
            location = appointment.get("location") if isinstance(appointment, dict) else None
            lat = location.get("lat") if isinstance(location, dict) else None
            lng = location.get("lng") if isinstance(location, dict) else None
            # An address-only location has no coordinates to reuse; it is skipped, never given made-up ones.
            if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)) or isinstance(lat, bool) or isinstance(lng, bool):
                continue
            if south <= lat <= north and west <= lng <= east:
                seen.add((round(float(lat), 5), round(float(lng), 5)))
    return [{"lat": lat, "lng": lng} for lat, lng in sorted(seen)]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--glob", default=DEFAULT_GLOB, help="the imported days' api-request.json files, from the repository root")
    parser.add_argument("--box", default=",".join(str(value) for value in DEFAULT_BOX), help="west,south,east,north")
    parser.add_argument("--minimum", type=int, default=500, help="refuse a pool smaller than this")
    args = parser.parse_args(argv)
    try:
        box_values = tuple(float(value) for value in args.box.split(","))
    except ValueError:
        box_values = ()
    if len(box_values) != 4:
        print("--box is west,south,east,north", file=sys.stderr)
        return 2
    box = (box_values[0], box_values[1], box_values[2], box_values[3])
    root = Path(__file__).resolve().parents[2]
    files = sorted(Path(path) for path in glob.glob(str(root / args.glob)))
    if not files:
        print(f"No imported days match {args.glob}", file=sys.stderr)
        return 1
    try:
        points = collect(files, box)
    except ValueError as error:
        print(f"Cannot build the pool: {error}", file=sys.stderr)
        return 1
    if len(points) < args.minimum:
        print(f"Only {len(points)} distinct points in {len(files)} days; a pool needs {args.minimum}", file=sys.stderr)
        return 1
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"source": args.glob, "days": len(files), "box": list(box), "points": points}) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(points)} points from {len(files)} days into {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
