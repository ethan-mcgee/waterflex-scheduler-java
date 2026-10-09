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
            "build-and-unit": ["java-javac", "java-nullability", "portal-checks", "infrastructure-evidence"],
            "booking-and-optimizer-integration": ["integration-core", "integration-remote"],
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
        artifacts = []
        for key in ["integration-core", "integration-remote"]:
            job = self.jobs[key]
            self.assertIn("image: postgres:16", job)
            self.assertIn("npm ci && npm run prisma:generate", job)
            self.assertIn("./mvnw", job)
            self.assertIn("infra/fixture-routing.mjs", job)
            self.assertIn("if: failure()", job)
            artifacts.extend(re.findall(r"          name: (.*-diagnostics)", job))
        self.assertEqual(len(artifacts), 2)
        self.assertEqual(len(set(artifacts)), 2)

    def test_remote_calculation_covers_every_public_api_calculation(self):
        remote = self.jobs["integration-remote"]
        # Booking, daily proposals and repairs each run against the remote solver through the public API,
        # so each smoke's metro must be routed to the fixture router.
        for scenario, smoke, metro in [("booking", "api-booking", "api-booking-smoke"), ("dispatch", "api-dispatch", "api-dispatch-smoke"),
                                       ("repair", "api-repair", "api-repair-smoke")]:
            self.assertIn(f"remote-{scenario} npm run test:{smoke}:integration", remote)
            self.assertIn(f"{metro}=http://127.0.0.1:18001", remote)
        self.assertNotIn("Start fixture routing and scheduler", remote)

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
