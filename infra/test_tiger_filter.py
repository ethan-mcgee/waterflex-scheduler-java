"""Regression coverage for infra/map-import/filter_tiger.py, which keeps only TIGER ranges near the depots."""
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "filter_tiger", Path(__file__).resolve().parent / "map-import" / "filter_tiger.py")
filter_tiger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(filter_tiger)

HEADER = "from;to;interpolation;street;city;state;postcode;geometry"
OMAHA = "100;198;even;Dodge Street;Omaha;NE;68102;LINESTRING(-95.9400 41.2600,-95.9390 41.2600)"
# About 72 miles east of the Council Bluffs depot: inside 65 + 10 miles only because of the margin.
MARGIN_EDGE = "1;99;odd;Edge Road;Anita;IA;50020;LINESTRING(-94.6500 41.2000,-94.6490 41.2000)"
DENVER = "1;99;odd;Colfax Avenue;Denver;CO;80202;LINESTRING(-104.9900 39.7400,-104.9890 39.7400)"
MIAMI = "2;98;even;Flagler Street;Miami;FL;33130;LINESTRING(-80.1900 25.7700,-80.1890 25.7700)"
COVERAGE = {"circles": [{"lat": 41.2148, "lng": -96.1177, "radiusMi": 65},
                        {"lat": 41.1592, "lng": -95.9362, "radiusMi": 65}],
            "marginMi": 10}


class TigerFilterTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def coverage(self, value=None):
        path = self.root / "coverage.json"
        path.write_text(json.dumps(COVERAGE if value is None else value), encoding="utf-8")
        return path

    def source(self, members):
        path = self.root / "national.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            for name, lines in members.items():
                data = ("\n".join(lines) + "\n").encode("utf-8")
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        return path

    def run_filter(self, members, coverage=None, output="filtered.tar.gz"):
        target = self.root / output
        stats = filter_tiger.filter_tiger(self.coverage(coverage), self.source(members), target)
        return stats, target

    @staticmethod
    def read(path):
        with tarfile.open(path, "r:gz") as archive:
            return {member.name: archive.extractfile(member).read().decode("utf-8") for member in archive}

    def test_keeps_ranges_inside_the_depot_envelope_and_omits_empty_counties(self):
        stats, target = self.run_filter({
            "tiger/31055.csv": [HEADER, OMAHA, DENVER],
            "tiger/19029.csv": [HEADER, MARGIN_EDGE],
            "tiger/12086.csv": [HEADER, MIAMI],
            "README.txt": ["not a county file"],
        })
        self.assertEqual(stats, {"filesTotal": 3, "filesKept": 2, "rowsTotal": 4, "rowsKept": 2, "marginMi": 10.0})
        self.assertEqual(self.read(target), {
            "tiger/19029.csv": f"{HEADER}\n{MARGIN_EDGE}\n",
            "tiger/31055.csv": f"{HEADER}\n{OMAHA}\n",
        })

    def test_the_margin_is_what_keeps_the_edge_range(self):
        stats, _ = self.run_filter({"a.csv": [HEADER, OMAHA, MARGIN_EDGE]}, {**COVERAGE, "marginMi": 0})
        self.assertEqual(stats["rowsKept"], 1)

    def test_output_is_byte_for_byte_reproducible(self):
        members = {"b.csv": [HEADER, OMAHA], "a.csv": [HEADER, MARGIN_EDGE]}
        _, first = self.run_filter(members, output="first.tar.gz")
        _, second = self.run_filter(members, output="second.tar.gz")
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_malformed_geometry_fails_with_its_location(self):
        for geometry in ["POINT(-95.94 41.26)", "LINESTRING(-95.94)", "LINESTRING(-95.94 abc)",
                         "LINESTRING(-195.94 41.26)", "LINESTRING(nan 41.26)", ""]:
            with self.subTest(geometry=geometry), self.assertRaisesRegex(filter_tiger.TigerFilterError, "c.csv:3"):
                self.run_filter({"c.csv": [HEADER, OMAHA, f"1;9;odd;X St;Omaha;NE;68102;{geometry}"]})

    def test_rows_with_the_wrong_field_count_fail(self):
        with self.assertRaisesRegex(filter_tiger.TigerFilterError, "expected 8 fields"):
            self.run_filter({"c.csv": [HEADER, "1;9;odd;X St;68102;LINESTRING(-95.94 41.26,-95.93 41.26)"]})

    def test_missing_columns_fail(self):
        with self.assertRaisesRegex(filter_tiger.TigerFilterError, "missing TIGER columns"):
            self.run_filter({"c.csv": ["from;to;street;geometry", "1;9;X St;LINESTRING(-95.94 41.26,-95.93 41.26)"]})
        with self.assertRaisesRegex(filter_tiger.TigerFilterError, "missing TIGER columns"):
            self.run_filter({"c.csv": []})

    def test_invalid_coverage_fails_instead_of_keeping_or_dropping_everything(self):
        members = {"c.csv": [HEADER, OMAHA]}
        for coverage in [{"marginMi": 10}, {"circles": [], "marginMi": 10}, {"circles": None, "marginMi": 10},
                         {"circles": COVERAGE["circles"]},
                         {"circles": [{"lat": None, "lng": -96.0, "radiusMi": 65}], "marginMi": 10},
                         {"circles": [{"lat": 41.2, "lng": -96.0, "radiusMi": True}], "marginMi": 10},
                         {"circles": [{"lat": 41.2, "lng": -96.0, "radiusMi": 0}], "marginMi": 10},
                         {"circles": COVERAGE["circles"], "marginMi": -1}, []]:
            with self.subTest(coverage=coverage), self.assertRaises(filter_tiger.TigerFilterError):
                self.run_filter(members, coverage)

    def test_no_kept_ranges_or_no_csv_files_fail(self):
        with self.assertRaisesRegex(filter_tiger.TigerFilterError, "No TIGER ranges"):
            self.run_filter({"c.csv": [HEADER, DENVER, MIAMI]})
        with self.assertRaisesRegex(filter_tiger.TigerFilterError, "no TIGER CSV files"):
            self.run_filter({"README.txt": ["nothing"]})


if __name__ == "__main__":
    unittest.main()
