"""Guard scheduler-proxy's routing: pools as failure-tracking upstream groups, no resend, bounded booking waits."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


class ProxyRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.nginx = read("deploy/vm/nginx.conf")
        cls.api = read("deploy/vm/scheduler-api.conf")

    def test_each_pool_is_an_upstream_group_that_follows_docker_dns(self):
        # Docker's DNS lists an unhealthy replica until it stops, so nginx's own failure count is what leaves a hung
        # replica out; resolve keeps replicas added or removed by scaling in the group without a restart.
        for pool, host in [("booking_pool", "scheduler-booking"), ("daily_pool", "scheduler-daily")]:
            with self.subTest(pool=pool):
                group = re.search(rf"upstream {pool} \{{(.*?)\}}", self.nginx, re.S)
                self.assertIsNotNone(group)
                self.assertIn(f"zone {pool} ", group.group(1))
                self.assertIn(f"server {host}:8000 resolve max_fails=1 fail_timeout=30s;", group.group(1))
        self.assertIn("resolver 127.0.0.11 valid=10s", self.nginx)

    def test_api_calls_reach_the_pools_only_through_the_groups(self):
        self.assertIn("set $booking_pool http://booking_pool;", self.api)
        self.assertIn("set $daily_pool http://daily_pool;", self.api)
        self.assertNotRegex(self.api, r"http://scheduler-(booking|daily):8000")

    def test_a_call_is_never_resent(self):
        # Idempotent by requestId, but a resend could start a second calculation while the first still runs.
        self.assertIn("proxy_next_upstream off;", self.nginx)
        self.assertNotIn("proxy_next_upstream ", self.api)

    def test_booking_pool_waits_are_bounded_and_daily_ones_are_not_cut(self):
        booking = re.search(r"location /api/v1/ \{([^}]*)\}", self.api)
        self.assertIsNotNone(booking)
        self.assertIn("proxy_read_timeout 60s;", booking.group(1))
        self.assertIn("proxy_pass $booking_pool;", booking.group(1))
        # A synchronous daily proposal routes for up to 30 s and solves for up to 20 s more.
        self.assertIn("proxy_read_timeout 300s;", self.nginx)
        for daily in re.findall(r"location [^{]*(?:daily|repairs|requests)[^{]*\{([^}]*)\}", self.api):
            self.assertNotIn("proxy_read_timeout", daily)


if __name__ == "__main__":
    unittest.main()
