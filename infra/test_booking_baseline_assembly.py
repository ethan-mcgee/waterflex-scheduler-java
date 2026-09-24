import gzip
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("assembly", Path(__file__).with_name("assemble-original-booking-baseline.py"))
assert spec is not None and spec.loader is not None
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="waterflex-assembly-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.primary = self.root / "primary.jsonl"
        self.tail = self.root / "tail.jsonl"
        self.output = self.root / "assembled.jsonl"
        self.provenance = {"type": "provenance", "revision": "2d17885141d4a901b06b3f9732530db85c951568",
                           "artifactSha256": "a" * 64, "seed": 17, "dates": ["2026-09-25"], "requests": 30,
                           "concurrencyValues": [1, 5, 10], "caches": ["cold", "warm"], "serverMode": "legacy",
                           "sizes": [20, 30, 50], "workloads": ["SPARSE", "CLUSTERED", "DISPERSED", "MIXED_SKILL",
                                                               "TIGHT_WINDOW", "ABSENCE", "NEAR_CAPACITY"]}
        self.primary_cases = []
        self.tail_cases = []
        for size, workload, concurrency, cache in itertools.product(self.provenance["sizes"], self.provenance["workloads"],
                                                                   [1, 5, 10], ["cold", "warm"]):
            row = {"type": "case", "size": size, "workload": workload, "concurrency": concurrency, "cache": cache,
                   "independentlyValidated": True, "promiseViolations": 0,
                   "attempts": [{"completed": None} for _ in range(30)], "after": {"routingIdentity": "unit-fixture"}}
            (self.tail_cases if size == 50 and workload == "NEAR_CAPACITY" else self.primary_cases).append(row)

    def write_sources(self):
        tail_provenance = {**self.provenance, "sizes": [50], "workloads": ["NEAR_CAPACITY"]}
        for path, rows in [(self.primary, [self.provenance] + self.primary_cases), (self.tail, [tail_provenance] + self.tail_cases)]:
            path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf8")

    def test_complete_partition_preserves_raw_sources_and_unknown_completion(self):
        self.write_sources()
        assembly.assemble(self.primary, self.tail, self.output)
        rows = [json.loads(line) for line in self.output.read_text().splitlines()]
        self.assertEqual(127, len(rows))
        self.assertIsNone(rows[1]["attempts"][0]["completed"])
        for source, original in zip(rows[0]["assembly"]["sources"], [self.primary, self.tail], strict=True):
            raw = gzip.decompress(self.output.with_name(source["archive"]).read_bytes())
            self.assertEqual(original.read_bytes(), raw)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), source["rawSha256"])
        with self.assertRaises(FileExistsError):
            assembly.assemble(self.primary, self.tail, self.output)

    def test_missing_case_does_not_publish_partial_matrix(self):
        self.tail_cases.pop()
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            assembly.assemble(self.primary, self.tail, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob("*.gz")))

    def test_duplicate_case_is_not_silently_selected(self):
        self.tail_cases.append(self.tail_cases[0])
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "duplicated"):
            assembly.assemble(self.primary, self.tail, self.output)

    def test_null_artifact_hash_fails_explicitly(self):
        self.provenance["artifactSha256"] = None
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "artifact hash"):
            assembly.assemble(self.primary, self.tail, self.output)


if __name__ == "__main__":
    unittest.main()
