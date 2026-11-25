"""Tests for infra/vm-perf/build_points.py."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("vm_perf_build_points", Path(__file__).parent / "vm-perf" / "build_points.py")
assert _SPEC is not None and _SPEC.loader is not None
build_points = importlib.util.module_from_spec(_SPEC)
sys.modules["vm_perf_build_points"] = build_points
_SPEC.loader.exec_module(build_points)

BOX = (-121.5, 34.3, -119.4, 36.3)


def request(*locations) -> dict:
    return {"serviceDate": "2026-11-17", "snapshot": {"appointments": [{"id": f"a{i}", "location": location} for i, location in enumerate(locations)]}}


class BuildPointsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def file(self, name: str, body) -> Path:
        path = self.root / name
        path.write_text(body if isinstance(body, str) else json.dumps(body), encoding="utf-8")
        return path

    def test_distinct_points_inside_the_box_only(self) -> None:
        a = self.file("a.json", request({"lat": 35.6, "lng": -120.7}, {"lat": 35.600001, "lng": -120.700001}, {"lat": 40.0, "lng": -120.7}))
        b = self.file("b.json", request({"lat": 35.5, "lng": -120.6}, {"address": {"line1": "1 Main"}}, {"lat": True, "lng": -120.6}))
        points = build_points.collect([a, b], BOX)
        self.assertEqual(points, [{"lat": 35.5, "lng": -120.6}, {"lat": 35.6, "lng": -120.7}],
                         "rounded duplicates merge; out-of-box, address-only and non-number points are left out, never made up")

    def test_an_unreadable_day_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            build_points.collect([self.file("bad.json", "{not json")], BOX)
        with self.assertRaises(ValueError):
            build_points.collect([self.file("shape.json", {"snapshot": {}})], BOX)


if __name__ == "__main__":
    unittest.main()
