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
                   "datasetFingerprint": f"{size}/{workload}/{concurrency}/{cache}",
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

    def test_separate_absence_group_preserves_the_same_complete_matrix(self):
        absence = self.root / "absence.jsonl"
        rows = [row for row in self.primary_cases if row["size"] == 50 and row["workload"] == "ABSENCE"]
        self.primary_cases = [row for row in self.primary_cases if row not in rows]
        self.write_sources()
        provenance = {**self.provenance, "sizes": [50], "workloads": ["ABSENCE"]}
        absence.write_text("".join(json.dumps(row) + "\n" for row in [provenance] + rows), encoding="utf8")
        assembly.assemble(self.primary, self.tail, self.output, absence)
        combined = [json.loads(line) for line in self.output.read_text().splitlines()]
        self.assertEqual(127, len(combined))
        self.assertEqual(3, len(combined[0]["assembly"]["sources"]))
        self.assertEqual(6, sum(row.get("sourceRunIndex") == 2 for row in combined[1:]))

    def test_timeout_continuation_preserves_failure_and_excluded_observations(self):
        absence, middle = self.root / "absence.jsonl", self.root / "middle.jsonl"
        for path, workloads in [(absence, ["ABSENCE"]), (middle, ["DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW"])]:
            cases = [row for row in self.primary_cases if row["size"] == 50 and row["workload"] in workloads]
            provenance = {**self.provenance, "sizes": [50], "workloads": workloads, "legacyMeasurementTimeoutMs": 600000}
            path.write_text("".join(json.dumps(row) + "\n" for row in [provenance] + cases), encoding="utf8")
        self.primary_cases = self.primary_cases[:98]
        failure = {"type": "failure", "message": "Original server transport ended without a complete response"}
        self.write_sources()
        with self.primary.open("a", encoding="utf8") as stream:
            stream.write(json.dumps(failure) + "\n")
        assembly.assemble(self.primary, self.tail, self.output, absence, middle)
        combined = [json.loads(line) for line in self.output.read_text().splitlines()]
        self.assertEqual(127, len(combined))
        self.assertEqual(18, sum(row.get("sourceRunIndex") == 3 for row in combined[1:]))
        source = combined[0]["assembly"]["sources"][0]
        self.assertEqual([failure], source["failureRecords"])
        self.assertEqual(2, len(source["excludedCaseKeys"]))

    def test_middle_continuation_cannot_implicitly_replace_other_partitions(self):
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "requires the declared absence"):
            assembly.assemble(self.primary, self.tail, self.output, middle_tail=self.tail)

    def test_near_timeout_retains_sequential_cases_and_continues_parallel_cases(self):
        absence, middle, parallel = (self.root / name for name in ("absence.jsonl", "middle.jsonl", "parallel.jsonl"))
        for path, workloads in [(absence, ["ABSENCE"]), (middle, ["DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW"])]:
            cases = [row for row in self.primary_cases if row["size"] == 50 and row["workload"] in workloads]
            provenance = {**self.provenance, "sizes": [50], "workloads": workloads}
            path.write_text("".join(json.dumps(row) + "\n" for row in [provenance] + cases), encoding="utf8")
        continued = [row for row in self.tail_cases if row["concurrency"] > 1]
        provenance = {**self.provenance, "sizes": [50], "workloads": ["NEAR_CAPACITY"], "concurrencyValues": [5, 10], "legacyMeasurementTimeoutMs": 600000}
        parallel.write_text("".join(json.dumps(row) + "\n" for row in [provenance] + continued), encoding="utf8")
        self.tail_cases = self.tail_cases[:2]
        self.write_sources()
        failure = {"type": "failure", "message": "Original transport timeout"}
        with self.tail.open("a", encoding="utf8") as stream:
            stream.write(json.dumps(failure) + "\n")
        assembly.assemble(self.primary, self.tail, self.output, absence, middle, parallel)
        rows = [json.loads(line) for line in self.output.read_text().splitlines()]
        self.assertEqual(127, len(rows))
        self.assertEqual(2, sum(row.get("sourceRunIndex") == 1 for row in rows[1:]))
        self.assertEqual(4, sum(row.get("sourceRunIndex") == 4 for row in rows[1:]))
        self.assertEqual([failure], rows[0]["assembly"]["sources"][1]["failureRecords"])

    def recheck_sources(self):
        self.write_sources()
        assembly.assemble(self.primary, self.tail, self.output)
        paths = []
        for workload, concurrency in [("DISPERSED", 10), ("MIXED_SKILL", 1)]:
            path = self.root / f"recheck-{workload}.jsonl"
            provenance = {**self.provenance, "sizes": [20], "workloads": [workload], "concurrencyValues": [concurrency]}
            rows = [row for row in self.primary_cases if row["size"] == 20 and row["workload"] == workload and row["concurrency"] == concurrency]
            path.write_text("".join(json.dumps(row) + "\n" for row in [provenance] + rows), encoding="utf8")
            paths.append(path)
        return paths

    def test_declared_rechecks_preserve_the_original_matrix_and_unknown_completion(self):
        dispersed, mixed = self.recheck_sources()
        corrected = self.root / "corrected.jsonl"
        original = self.output.read_bytes()
        assembly.correct_rechecks(self.output, dispersed, mixed, corrected)
        rows = [json.loads(line) for line in corrected.read_text().splitlines()]
        self.assertEqual(127, len(rows))
        self.assertEqual(4, len(rows[0]["correction"]["replacedCaseKeys"]))
        self.assertEqual(4, sum("correctionSourceIndex" in row for row in rows[1:]))
        self.assertTrue(all(row["attempts"][0]["completed"] is None for row in rows[1:]))
        self.assertEqual(original, gzip.decompress(corrected.with_name(corrected.name + ".source-0.gz").read_bytes()))

    def test_recheck_with_changed_request_stream_cannot_replace_a_case(self):
        dispersed, mixed = self.recheck_sources()
        rows = [json.loads(line) for line in dispersed.read_text().splitlines()]
        rows[1]["datasetFingerprint"] = "different"
        dispersed.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf8")
        corrected = self.root / "corrected.jsonl"
        with self.assertRaisesRegex(ValueError, "request stream differs"):
            assembly.correct_rechecks(self.output, dispersed, mixed, corrected)
        self.assertFalse(corrected.exists())


if __name__ == "__main__":
    unittest.main()
