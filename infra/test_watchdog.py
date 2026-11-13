"""Guard the VM's replica watchdog: it restarts a hung scheduler replica, never one that only waits on a dependency."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


class WatchdogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = read("infra/watchdog-vm.sh")
        cls.compose = read("deploy/vm/docker-compose.yml")

    def test_unhealthy_means_not_ready_so_liveness_decides(self):
        # The replicas' Docker healthcheck is /health (readiness: database, schema, routing). A restart on that alone
        # would restart every replica while routing is down, so the watchdog asks /health/live, which reads nothing.
        replica = self.compose[self.compose.index("x-scheduler-replica:"):self.compose.index("x-owner-connection:")]
        self.assertIn("GET /health HTTP/1.0", replica)
        self.assertIn('"running unhealthy") ;;', self.script)
        self.assertIn("GET /health/live HTTP/1.0", self.script)
        self.assertRegex(self.script, r"if live \"\$id\"; then\n\s+log .*left running\"\n\s+continue")
        self.assertIn("for attempt in 1 2 3", self.script)

    def test_it_watches_the_scheduler_pools_only(self):
        self.assertIn("for service in scheduler-booking scheduler-daily; do", self.script)
        self.assertNotRegex(self.script, r"routing-omaha|scheduler-db|scheduler-proxy")

    def test_an_operator_pause_is_left_alone(self):
        self.assertRegex(self.script, r'"paused "\*\) log .*left alone"; continue ;;')

    def test_runs_do_not_overlap_and_a_restart_is_bounded(self):
        self.assertIn("flock -n 9 || exit 0", self.script)
        self.assertIn('docker restart -t "$grace" "$id"', self.script)
        self.assertIn("WATCHDOG_STOP_SECONDS:-10", self.script)

    def test_the_docs_schedule_it(self):
        self.assertIn("bash infra/watchdog-vm.sh", read("deploy/vm/README.md"))
        self.assertIn("infra/watchdog-vm.sh", read("docs/operations-runbook.md"))


if __name__ == "__main__":
    unittest.main()
