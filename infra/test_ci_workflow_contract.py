"""Guard the parallel CI gates and coverage that are easy to lose when moving jobs."""
import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"


class ParallelCiContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = dict(re.findall(
            r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)",
            cls.text.split("jobs:\n", 1)[1], re.M | re.S))

    def test_aggregates_require_success_from_every_dependency(self):
        groups = {
            "build-and-unit": ["java-javac", "java-nullability", "portal-checks", "infrastructure-evidence", "api-compatibility"],
            "booking-and-optimizer-integration": ["integration-core", "vm-stack"],
        }
        for gate, dependencies in groups.items():
            with self.subTest(gate=gate):
                job = self.jobs[gate]
                self.assertIn("    if: always()\n", job)
                self.assertIn(f"needs: [{', '.join(dependencies)}]", job)
                expression = re.search(r"ALL_SUCCEEDED: \$\{\{ (.*?) \}\}", job).group(1)
                self.assertEqual(expression, " && ".join(
                    f"needs.{key}.result == 'success'" for key in dependencies))
                self.assertIn('run: test "$ALL_SUCCEEDED" = true', job)
                for key in dependencies:
                    self.assertIn(key, self.jobs)
                    self.assertNotIn("    needs:", self.jobs[key])
                    self.assertNotIn("continue-on-error:", self.jobs[key])

    def test_the_v1_contract_is_compared_with_the_one_it_replaces_by_a_pinned_oasdiff(self):
        job = self.jobs["api-compatibility"]
        self.assertRegex(job, r"oasdiff/releases/download/v\d+\.\d+\.\d+/oasdiff_\d+\.\d+\.\d+_linux_amd64\.tar\.gz")
        self.assertRegex(job, r'echo "[0-9a-f]{64}  \$RUNNER_TEMP/oasdiff\.tar\.gz" \| sha256sum --check')
        self.assertIn("github.event.pull_request.base.sha || github.event.before", job)
        self.assertIn('git fetch --no-tags --depth=1 origin "$BASE_SHA"', job)
        self.assertIn('python3 infra/openapi_compat.py --against "$BASE_SHA" --oasdiff "$RUNNER_TEMP/oasdiff"', job)

    def test_the_vm_stack_is_built_and_checked_through_the_proxy(self):
        job = self.jobs["vm-stack"]
        self.assertIn('run: bash infra/check-vm-stack.sh "$RUNNER_TEMP/vm-stack"', job)
        self.assertIn('docker login --username "$DOCKERHUB_USERNAME" --password-stdin', job)
        self.assertIn("cache-dependency-path: infra/host-conformance/package-lock.json", job)
        self.assertIn("          name: vm-stack-diagnostics", job)
        self.assertEqual(job.count("if: failure()"), 2)
        script = (WORKFLOW.parents[2] / "infra/check-vm-stack.sh").read_text(encoding="utf-8")
        for check in ("promtool check config", "-tls1_1 -cipher 'DEFAULT@SECLEVEL=0'", "ALPN protocol: h2", "00000000000000c3",
                      "--token-env SCHEDULER_STUDY_TOKEN", "--command=revoke-token", "stop scheduler-booking scheduler-daily",
                      "protocols+=(--http2)"):
            self.assertIn(check, script)

    def test_postgres_service_pulls_are_authenticated(self):
        # Anonymous Docker Hub pulls share a rate limit with every job on the runner's IP; logged-in pulls use the account's own limit.
        services = self.text.count("image: postgres:16\n")
        self.assertGreater(services, 0)
        login = """image: postgres:16
        credentials:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}
"""
        self.assertEqual(self.text.count(login), services)

    def test_integration_isolation_and_unique_diagnostics(self):
        job = self.jobs["integration-core"]
        self.assertIn("image: postgres:16", job)
        self.assertIn("npm ci && npm run prisma:generate", job)
        self.assertIn("./mvnw", job)
        self.assertIn("infra/fixture-routing.mjs", job)
        self.assertIn("if: failure()", job)
        artifacts = re.findall(r"          name: (.*-diagnostics)", self.text)
        self.assertEqual(len(artifacts), len(set(artifacts)), "each job uploads its own diagnostics")

    def test_the_remote_solver_is_gone(self):
        # The owner removed the remote solver pool on 2026-11-13; the embedded solver is the only calculation path.
        self.assertNotIn("integration-remote", self.jobs)
        self.assertNotIn("solver-service", self.text)
        self.assertNotIn("SCHEDULER_CALCULATION_MODE", self.text)

    def test_retired_portal_endpoints_are_not_exercised(self):
        self.assertNotIn("integration-reservations", self.jobs)
        self.assertNotIn("integration-time-off", self.jobs)
        for retired in ["test:booking:integration", "test:bounded-booking:integration", "test:search-cancellation:integration",
                        "test:reservation:integration", "test-booking-offer-limit", "BOOKING_OFFER_LIMIT",
                        "test:optimizer:integration", "test:time-off:integration", "test:multi-depot:integration",
                        "test:dealership:integration", "ROUTING_PREWARM_ENABLED"]:
            self.assertNotIn(retired, self.text)

    def test_evidence_job_has_java_for_real_process_regressions(self):
        self.assertIn("actions/setup-java@v6", self.jobs["infrastructure-evidence"])
        self.assertIn("java-version: '25'", self.jobs["infrastructure-evidence"])

    def test_both_compilers_verify_benchmark_reactor(self):
        for key, profile in [("java-javac", "campaign-benchmark"),
                             ("java-nullability", "campaign-benchmark,nullability")]:
            self.assertIn(f"./mvnw -P{profile} clean verify", self.jobs[key])
            self.assertNotIn("-DskipTests", self.jobs[key])


if __name__ == "__main__":
    unittest.main()
