"""Keep the scheduler VM's default CPU caps within its 8 vCPU, so the pools' measured latency holds under combined load."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VM_VCPU = 8
# The owner's layout (2026-11-13): booking one replica of two slots on 2 vCPU, daily (what overnight uses) the same.
POOLS = {"scheduler-booking": ("SCHEDULER_BOOKING", 2), "scheduler-daily": ("SCHEDULER_DAILY", 2)}
# Profiles that run on the VM by default. Nominatim is opt-in (GEOCODING_MODE=COORDINATES_REQUIRED is the default) and
# needs another cap lowered to fit, which the runbook's "CPU sizing" says.
RUNS_BY_DEFAULT = {None, "monitoring"}


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def services(text):
    body = text[text.index("\nservices:\n"):text.index("\nvolumes:\n")]
    return dict(re.findall(r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", body, re.M | re.S))


def default(block, key):
    """The default of a "${NAME:-value}" setting in a service block, or None when the block does not set it."""
    match = re.search(rf'{key}: "?\$\{{(\w+):-([\d.]+)\}}"?', block)
    return (match.group(1), float(match.group(2))) if match else None


def profile(block):
    match = re.search(r'profiles: \["([\w-]+)"\]', block)
    return match.group(1) if match else None


class VmCpuBudgetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.services = services(read("deploy/vm/docker-compose.yml"))
        cls.example = dict(re.findall(r"^(\w+)=(\S+)$", read("deploy/vm/.env.example"), re.M))

    def caps(self, overrides):
        total, seen = 0.0, {}
        for name, block in self.services.items():
            if profile(block) not in RUNS_BY_DEFAULT:
                continue
            cpus = default(block, "cpus")
            # The one-shot admin command and the TLS helper exit after their work and take no cores while serving.
            if cpus is None:
                self.assertRegex(block, r"restart: \"?no\"?|command=migrate|scheduler-image", name)
                continue
            replicas = default(block, "replicas")
            count = float(overrides.get(replicas[0], replicas[1])) if replicas else 1.0
            share = float(overrides.get(cpus[0], cpus[1])) * count
            seen[name] = share
            total += share
        return total, seen

    def test_the_default_layout_fits_the_vm(self):
        total, seen = self.caps({})
        self.assertLessEqual(total, VM_VCPU, seen)
        for required in ["scheduler-booking", "scheduler-daily", "scheduler-proxy", "scheduler-db", "routing-omaha", "prometheus"]:
            self.assertIn(required, seen, "every long-running service has a CPU cap")

    def test_the_example_environment_fits_the_vm(self):
        total, seen = self.caps(self.example)
        self.assertLessEqual(total, VM_VCPU, seen)

    def test_each_pool_is_one_replica_of_two_slots_on_two_vcpu(self):
        for name, (prefix, cores) in POOLS.items():
            block = self.services[name]
            with self.subTest(pool=name):
                self.assertEqual(default(block, "replicas"), (f"{prefix}_REPLICAS", 1.0))
                self.assertEqual(default(block, "cpus"), (f"{prefix}_CPUS", float(cores)))
                self.assertEqual(default(block, "SCHEDULER_SEARCH_CAPACITY"), (f"{prefix}_SEARCH_CAPACITY", float(cores)))
                self.assertEqual(self.example[f"{prefix}_REPLICAS"], "1")
                self.assertEqual(self.example[f"{prefix}_CPUS"], str(cores))
                self.assertEqual(self.example[f"{prefix}_SEARCH_CAPACITY"], str(cores))
        self.assertEqual(default(self.services["scheduler-daily"], "SCHEDULER_SEARCH_BACKGROUND_LIMIT"),
                         ("SCHEDULER_DAILY_BACKGROUND_LIMIT", 2.0), "overnight runs both daily slots")

    def test_a_cap_over_budget_is_caught(self):
        total, _ = self.caps({"ROUTING_CPUS": "3"})
        self.assertGreater(total, VM_VCPU)


if __name__ == "__main__":
    unittest.main()
