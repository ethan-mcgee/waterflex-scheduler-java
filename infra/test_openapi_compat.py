import json
import pathlib
import subprocess
import tempfile
import unittest

import openapi_compat as gate

SPEC = pathlib.Path(__file__).resolve().parent.parent / "docs" / "api" / "openapi-v1.yaml"
PATTERN = {"id": "request-property-pattern-added", "level": 3, "operation": "POST", "path": "/api/v1/booking/offers",
           "text": "added the pattern `^x$` to the request property `requestId`"}
ADDED = {"id": "new-optional-request-property", "level": 1, "operation": "POST", "path": "/api/v1/daily/proposals",
         "text": "added the new optional request property `snapshot/rates/arrivalBuffer`"}


def spec(version: str) -> str:
    return f"openapi: 3.1.0\ninfo:\n  title: Scheduler\n  version: {version}\npaths: {{}}\n"


class Oasdiff:
    """Stands in for oasdiff: answers each command with the JSON and exit code given, and records the calls."""

    def __init__(self, breaking=(), changelog=(), breaking_exit=None, changelog_exit=0, stdout=None):
        self.answers = {"breaking": (list(breaking), 1 if breaking and breaking_exit is None else breaking_exit or 0),
                        "changelog": (list(changelog), changelog_exit)}
        self.stdout = stdout
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        changes, code = self.answers[args[1]]
        out = self.stdout if self.stdout is not None else json.dumps(changes)
        return subprocess.CompletedProcess(args, code, stdout=out, stderr="Error: could not load the spec")


class OpenApiCompatTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def files(self, before: str, after: str):
        base = pathlib.Path(self.directory.name, "base.yaml")
        head = pathlib.Path(self.directory.name, "head.yaml")
        base.write_text(spec(before), encoding="utf-8")
        head.write_text(spec(after), encoding="utf-8")
        return base, head

    def test_the_committed_spec_has_a_v1_version_the_gate_can_read(self):
        major, minor, patch = gate.spec_version(SPEC.read_text(encoding="utf-8"))
        self.assertEqual(gate.MAJOR, major)
        self.assertGreaterEqual(minor, 11)
        self.assertGreaterEqual(patch, 0)

    def test_the_version_is_the_info_key_and_not_a_nested_or_commented_one(self):
        text = ("# version: 9.9.9\nopenapi: 3.1.0\ninfo:\n  title: T\n  contact:\n    version: 7.0.0\n"
                "  # version: 8.0.0\n  version: '1.12.3'  # current\ncomponents:\n  version: 5.0.0\n")
        self.assertEqual((1, 12, 3), gate.spec_version(text))

    def test_a_missing_repeated_or_malformed_version_is_refused_rather_than_guessed(self):
        for text in ("info:\n  title: T\n", "info:\n  version: 1.0.0\n  version: 1.1.0\n", "info:\n  version: 1.0\n",
                     "info:\n  version: 1.0.0-draft\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                gate.spec_version(text)

    def test_an_unchanged_contract_passes(self):
        base, head = self.files("1.11.0", "1.11.0")
        self.assertEqual([], gate.check(base, head, run=Oasdiff()))

    def test_an_additive_change_passes_when_the_minor_version_rises(self):
        base, head = self.files("1.11.0", "1.12.0")
        self.assertEqual([], gate.check(base, head, run=Oasdiff(changelog=[ADDED])))

    def test_an_additive_change_without_a_new_version_fails(self):
        base, head = self.files("1.11.0", "1.11.0")
        problems = gate.check(base, head, run=Oasdiff(changelog=[ADDED]))
        self.assertEqual(1, len(problems))
        self.assertIn("info.version is still 1.11.0", problems[0])

    def test_a_breaking_change_fails_and_prints_the_lines_that_would_accept_it(self):
        base, head = self.files("1.11.0", "1.12.0")
        problems = gate.check(base, head, run=Oasdiff(breaking=[PATTERN], changelog=[PATTERN]))
        self.assertEqual(1, len(problems))
        self.assertIn("[request-property-pattern-added] POST /api/v1/booking/offers added the pattern", problems[0])
        self.assertTrue(problems[0].endswith(
            "To accept:\nPOST /api/v1/booking/offers added the pattern `^x$` to the request property `requestId`"))

    def test_a_warning_blocks_and_an_info_finding_does_not(self):
        base, head = self.files("1.11.0", "1.12.0")
        warning = dict(PATTERN, id="request-property-pattern-changed", level=2)
        self.assertEqual(1, len(gate.check(base, head, run=Oasdiff(breaking=[warning]))))
        self.assertEqual([], gate.check(base, head, run=Oasdiff(breaking=[ADDED], breaking_exit=0)))

    def test_the_version_may_not_go_down_or_leave_major_version_one(self):
        base, head = self.files("1.11.0", "1.10.0")
        self.assertIn("went down", gate.check(base, head, run=Oasdiff())[0])
        base, head = self.files("1.11.0", "2.0.0")
        self.assertIn("/api/v2", gate.check(base, head, run=Oasdiff(changelog=[ADDED]))[0])

    def test_oasdiff_runs_with_the_v1_policy_and_the_accepted_list(self):
        base, head = self.files("1.11.0", "1.11.0")
        tool = Oasdiff()
        gate.check(base, head, tool="/opt/oasdiff", run=tool)
        breaking, changelog = tool.calls
        self.assertEqual(["/opt/oasdiff", "breaking", str(base), str(head)], breaking[:4])
        self.assertEqual("WARN", breaking[breaking.index("--fail-on") + 1])
        self.assertEqual(str(gate.LEVELS), breaking[breaking.index("--severity-levels") + 1])
        self.assertEqual(str(gate.ACCEPTED), breaking[breaking.index("--err-ignore") + 1])
        self.assertEqual(str(gate.ACCEPTED), breaking[breaking.index("--warn-ignore") + 1])
        self.assertEqual(str(gate.LEVELS), changelog[changelog.index("--severity-levels") + 1])

    def test_new_response_enum_values_are_additive_by_the_v1_rule(self):
        lines = gate.LEVELS.read_text(encoding="utf-8").splitlines()
        self.assertIn("response-property-enum-value-added info", lines)
        self.assertTrue(gate.ACCEPTED.is_file())

    def test_oasdiff_failing_to_run_is_an_error_not_a_pass_or_a_finding(self):
        base, head = self.files("1.11.0", "1.11.0")
        with self.assertRaisesRegex(RuntimeError, "exit code 102: Error: could not load the spec"):
            gate.check(base, head, run=Oasdiff(breaking_exit=102))
        with self.assertRaisesRegex(RuntimeError, "changelog failed with exit code 1"):
            gate.check(base, head, run=Oasdiff(changelog_exit=1))

    def test_output_that_is_not_a_list_of_changes_is_refused(self):
        base, head = self.files("1.11.0", "1.11.0")
        for stdout in ("No changes", "{}", '[{"id": "x", "text": "y"}]', '[{"id": "x", "text": "y", "level": true}]'):
            with self.subTest(stdout=stdout), self.assertRaises(ValueError):
                gate.check(base, head, run=Oasdiff(stdout=stdout))

    def test_against_reads_the_spec_committed_at_a_ref(self):
        committed = gate.spec_at("HEAD").decode("utf-8")
        self.assertEqual(gate.MAJOR, gate.spec_version(committed)[0])
        with self.assertRaisesRegex(RuntimeError, "git show no-such-ref:docs/api/openapi-v1.yaml failed"):
            gate.spec_at("no-such-ref")

    def test_the_command_takes_two_specs_or_a_ref_but_not_both(self):
        for argv in ([], ["one.yaml"], ["--against", "HEAD", "one.yaml"]):
            with self.subTest(argv=argv), self.assertRaises(SystemExit):
                gate.main(argv)

    def test_an_accept_line_without_an_operation_is_the_text_alone(self):
        self.assertEqual("removed the schema `X`", gate.accept_line({"id": "a", "level": 3, "text": "removed the schema `X`"}))


if __name__ == "__main__":
    unittest.main()
