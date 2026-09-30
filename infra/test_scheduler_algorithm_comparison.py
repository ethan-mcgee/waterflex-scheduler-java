"""Regression checks for retained comparison evidence and presentation contracts."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("comparison", Path(__file__).with_name("build-scheduler-algorithm-comparison.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ComparisonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.summary, cls.manifest, _, cls.daily, cls.booking = report.validate()

    def test_served_and_incomplete_overlap_is_not_a_partition(self):
        measured = report.booking_metrics(self.booking["held", "SHARED"])
        self.assertEqual((measured["served"], measured["unsuccessful"], measured["incomplete"], measured["overlap"]), (334, 146, 153, 7))
        self.assertEqual(measured["served"] + measured["unsuccessful"], measured["n"])

    def test_missing_latency_and_service_fail_closed(self):
        for field in ("elapsedMs", "served"):
            rows = copy.deepcopy(self.booking["held", "SHARED"][:1])
            rows[0]["attempts"][0][field] = None
            with self.assertRaises(ValueError):
                report.booking_metrics(rows)
        for invalid in (None, float("nan"), float("inf"), True):
            with self.assertRaises(ValueError):
                report.percentile([invalid])

    def test_unknown_completion_is_explicit_not_false(self):
        row = copy.deepcopy(self.booking["held", "INSERTION"][0])
        self.assertIs(row["attempts"][0]["completed"], True)
        row["attempts"][0]["completed"] = None
        row["unknownSearchCompletion"] += 1
        measured = report.booking_metrics([row])
        self.assertEqual(measured["unknown"], 1)
        self.assertEqual(measured["incomplete"], row["incomplete"])

    def test_untested_is_not_zero(self):
        with self.assertRaises(ValueError):
            report.booking_metrics([])
        with self.assertRaises(ValueError):
            report.percentile([])
        report.matched_booking(self.summary, self.booking)
        untested = [p for p in self.summary["paired"] if p["stage"] == "held" and p["variant"] in ("EXPANDED", "RUIN_RECREATE")]
        self.assertTrue(all(p["meanSavingsCents"] is None and p["equalCustomerCases"] == 0 for p in untested))

    def test_chart_identity_survives_missing_variants(self):
        with patch.object(report, "chart", side_effect=lambda fig, name: fig):
            fig = report.quality_chart(self.summary)
            for axis in fig.axes:
                for line in axis.lines:
                    self.assertEqual((line.get_color(), line.get_marker()), report.IDENTITY[line.get_label()])
            shared = [line for axis in fig.axes for line in axis.lines if line.get_label() == "SHARED"]
            self.assertEqual(len(shared), 4)
            self.assertNotEqual(report.IDENTITY["SHARED"][0], report.IDENTITY["EXPANDED"][0])
            report.plt.close(fig)
            fig = report.booking_chart(self.summary)
            self.assertTrue(all(line.get_linestyle() == "None" for ax in fig.axes for line in ax.lines))
            report.plt.close(fig)

    def test_fleet_interaction_and_late_acceptance(self):
        pairs = report.daily_pairs(self.daily)
        for budget in (15, 30):
            rows = [p for p in pairs if p["variant"] == "KOPT" and p["budget"] == budget]
            self.assertEqual(sum(p["savings"] > 0 and p["fleet"] < 50 for p in rows), 6)
            self.assertEqual(sum(p["savings"] < 0 and p["fleet"] == 50 for p in rows), 2)
            self.assertLess(report.mean(p["savings"] for p in rows), 0)
        self.assertGreater(report.mean(p["savings"] for p in pairs if p["variant"] == "LATE_ACCEPTANCE" and p["budget"] == 30), 0)

    def test_daily_pairing_rejects_different_start_or_missing_cost(self):
        for missing in (False, True):
            rows = copy.deepcopy(self.daily)
            candidate = next(r for st, _, r in rows if st == "held" and r["variant"] == "KOPT")
            if missing:
                candidate["after"]["costCents"] = None
            else:
                candidate["before"]["costCents"] += 1
            with self.assertRaises((ValueError, AssertionError)):
                report.daily_pairs(rows)

    def test_quantiles_are_request_weighted_nearest_rank(self):
        self.assertEqual(report.percentile(list(range(1, 21))), 19)
        self.assertEqual(report.percentile([0]), 0)
        rows = self.booking["held", "BOUNDED"]
        values = sorted(a["elapsedMs"] for r in rows for a in r["attempts"])
        self.assertEqual(report.booking_metrics(rows)["pooled_p95"], values[455] / 1000)

    def test_narrative_and_tables_share_measured_values(self):
        with patch.object(report, "chart", side_effect=lambda fig, name: (report.plt.close(fig) or report.ASSETS / (name + ".png"))):
            blocks = report.report_blocks(self.summary, self.manifest, self.daily, self.booking)
        text = report.markdown(blocks)
        self.assertIn("BOUNDED serves 158/160", text)
        self.assertIn("| 1 | BOUNDED | 158/160 | 98.75% | -1.25 |", text)
        self.assertIn("7 SHARED requests were served despite incomplete search", text)
        self.assertIn("30 seconds: $0.30/case", text)
        self.assertIn("| LATE_ACCEPTANCE | -1.11 | +1.71 | +0.30 | 6/0/2 |", text)
        # Existing output must match the common content model, including tables.
        self.assertEqual(report.MD.read_text(encoding="utf-8"), text)
        report.check_outputs(blocks)


if __name__ == "__main__":
    unittest.main()
