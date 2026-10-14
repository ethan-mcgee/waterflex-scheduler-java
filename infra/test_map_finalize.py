"""Regression coverage for infra/map-import/finalize.py: a version's manifest lists both road graphs."""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

FINALIZE = Path(__file__).resolve().parent / "map-import" / "finalize.py"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class MapFinalizeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.versions = Path(self.directory.name)
        self.target = self.versions / "2026-10-13"
        self.target.mkdir()
        (self.target / "omaha.osm.pbf").write_bytes(b"merged")
        (self.target / "omaha.pmtiles").write_bytes(b"tiles")
        (self.target / "tiger-nominatim-preprocessed.csv.tar.gz").write_bytes(b"tiger")
        (self.target / "graph").mkdir()
        (self.target / "graph" / "properties").write_bytes(b"flexible")
        (self.target / "graph" / "gh.lock").write_bytes(b"")
        (self.target / "manifest.json").write_text(json.dumps({
            "version": "2026-10-13", "mergedSha256": sha256(b"merged"), "tiger": {"year": "2025", "sha256": sha256(b"tiger")}}))

    def tearDown(self):
        self.directory.cleanup()

    def run_finalize(self):
        return subprocess.run([sys.executable, str(FINALIZE), "2026-10-13", str(self.versions)], capture_output=True, text=True)

    def test_a_version_without_the_ch_graph_is_refused(self):
        result = self.run_finalize()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("graph-ch-car-v1", result.stderr)
        self.assertNotIn("artifacts", json.loads((self.target / "manifest.json").read_text()))

    def test_both_graphs_are_listed_with_their_checksums(self):
        (self.target / "graph-ch-car-v1").mkdir()
        (self.target / "graph-ch-car-v1" / "properties").write_bytes(b"prepared")
        (self.target / "graph-ch-car-v1" / "shortcuts_car").write_bytes(b"shortcuts")
        result = self.run_finalize()
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((self.target / "manifest.json").read_text())
        self.assertEqual({
            "omaha.pmtiles": sha256(b"tiles"), "tiger-nominatim-preprocessed.csv.tar.gz": sha256(b"tiger"),
            "graph/properties": sha256(b"flexible"),
            "graph-ch-car-v1/properties": sha256(b"prepared"), "graph-ch-car-v1/shortcuts_car": sha256(b"shortcuts"),
        }, manifest["artifacts"], "the lock file is left out, both graphs are in")
        self.assertEqual("11.0", manifest["tools"]["graphhopper"])


if __name__ == "__main__":
    unittest.main()
