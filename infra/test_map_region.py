"""Regression coverage for infra/map-import/region.py: the region specs and the checks prepare.sh relies on."""
import base64
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

MAP_IMPORT = Path(__file__).resolve().parent / "map-import"
REGIONS = MAP_IMPORT / "regions"
sys.path.insert(0, str(MAP_IMPORT))
import region  # noqa: E402

OMAHA_SOURCES = ["nebraska", "iowa", "missouri"]


def spec(**changes):
    base = {"name": "central-coast-ca", "sources": ["california"],
            "clip": {"west": -121.5, "south": 34.3, "east": -119.4, "north": 36.3},
            "samples": [{"name": "Paso Robles", "lat": 35.6266, "lng": -120.691}]}
    base.update(changes)
    return base


class RegionSpecTest(unittest.TestCase):
    def test_every_region_file_is_valid_and_named_after_its_file(self):
        files = sorted(REGIONS.glob("*.json"))
        self.assertGreaterEqual(len(files), 2)
        for file in files:
            self.assertEqual(file.stem, region.load(file)["name"], file.name)

    def test_the_omaha_region_reproduces_the_historical_preparation(self):
        omaha = region.load(REGIONS / "omaha.json")
        self.assertEqual(OMAHA_SOURCES, omaha["sources"])
        self.assertIsNone(omaha["clip"], "Omaha merges whole states, as before regions")
        self.assertEqual(10, len(omaha["samples"]))
        self.assertEqual("", region.clip_argument(omaha))

    def test_the_central_coast_region_clips_california(self):
        central = region.load(REGIONS / "central-coast-ca.json")
        self.assertEqual(["california"], central["sources"])
        self.assertEqual("-121.5,34.3,-119.4,36.3", region.clip_argument(central))
        names = {sample["name"] for sample in central["samples"]}
        self.assertTrue({"Paso Robles", "Santa Maria", "Bradley"} <= names)

    def test_a_manifest_without_a_region_is_omaha(self):
        manifest = {"sources": {s: {} for s in OMAHA_SOURCES}, "coverage": {}, "tiger": {"filter": {}}}
        self.assertEqual("omaha", region.from_manifest(manifest, REGIONS)["name"])
        self.assertTrue(region.prepared(manifest, region.load(REGIONS / "omaha.json")))
        self.assertFalse(region.prepared(manifest, region.load(REGIONS / "central-coast-ca.json")))

    def test_an_incomplete_manifest_is_not_prepared(self):
        central = region.load(REGIONS / "central-coast-ca.json")
        self.assertFalse(region.prepared({"region": central, "sources": {"california": {}}, "coverage": {}, "tiger": {}}, central))
        self.assertFalse(region.prepared({"region": central, "sources": {"california": {}}, "tiger": {"filter": {}}}, central))
        self.assertTrue(region.prepared({"region": central, "sources": {"california": {}}, "coverage": {}, "tiger": {"filter": {}}}, central))

    def test_invalid_specs_are_refused(self):
        for bad in [spec(name="Central Coast"), spec(sources=[]), spec(sources=["california", "california"]),
                    spec(clip={"west": -119.4, "south": 34.3, "east": -121.5, "north": 36.3}),
                    spec(clip={"west": -121.5, "south": 34.3, "east": -119.4}),
                    spec(samples=[]), spec(samples=[{"name": "Far away", "lat": 41.25, "lng": -95.93}]),
                    spec(samples=[{"name": "A", "lat": 35.6, "lng": -120.7}, {"name": "A", "lat": 35.5, "lng": -120.7}]),
                    spec(extra=1)]:
            with self.assertRaises(ValueError, msg=json.dumps(bad)):
                region.validate(bad)

    def test_the_command_line_decodes_and_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            encoded = base64.b64encode(json.dumps(spec()).encode()).decode()
            output = Path(directory) / "region.json"
            script = str(MAP_IMPORT / "region.py")
            self.assertEqual(0, subprocess.run([sys.executable, script, "decode", encoded, str(output)]).returncode)
            self.assertEqual(spec(), json.loads(output.read_text(encoding="utf-8")))
            sources = subprocess.run([sys.executable, script, "sources", str(output)], capture_output=True, text=True)
            self.assertEqual("california", sources.stdout.strip())
            clip = subprocess.run([sys.executable, script, "clip", str(output)], capture_output=True, text=True)
            self.assertEqual("-121.5,34.3,-119.4,36.3", clip.stdout.strip())
            manifest = Path(directory) / "manifest.json"
            manifest.write_text(json.dumps({"region": spec(), "sources": {"california": {}}, "coverage": {}, "tiger": {"filter": {}}}))
            self.assertEqual(0, subprocess.run([sys.executable, script, "prepared", str(manifest), str(output)]).returncode)
            manifest.write_text(json.dumps({"sources": {"nebraska": {}}, "coverage": {}, "tiger": {"filter": {}}}))
            self.assertEqual(1, subprocess.run([sys.executable, script, "prepared", str(manifest), str(output)]).returncode)
            broken = base64.b64encode(json.dumps(spec(sources=[])).encode()).decode()
            self.assertNotEqual(0, subprocess.run([sys.executable, script, "decode", broken, str(output)], capture_output=True).returncode)


if __name__ == "__main__":
    unittest.main()
