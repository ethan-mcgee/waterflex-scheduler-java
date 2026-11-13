"""Guard the VM's backups: what the dump holds, how it is kept, and a restore that works on a new server."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "scheduler-service/src/main/resources/db/migration"


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def created_roles(sql):
    return dict(re.findall(r"CREATE ROLE (\w+) ([A-Z ]+);", sql))


def memberships(sql):
    return set(re.findall(r"GRANT (scheduler_\w+) TO (\w+);", sql))


class BackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backup = read("infra/backup-vm.sh")
        cls.restore = read("infra/restore-vm.sh")
        cls.migrations = "\n".join(path.read_text(encoding="utf-8") for path in sorted(MIGRATIONS.glob("V*.sql")))

    def test_the_restore_recreates_every_role_the_migrations_create(self):
        # pg_dump carries grants but not roles; migrate cannot recreate them once the restored history says V1 ran.
        self.assertEqual(created_roles(self.restore), created_roles(self.migrations))
        self.assertIn(("scheduler_tenant", "scheduler_app"), memberships(self.restore))
        self.assertIn(("scheduler_tenant", "scheduler_owner"), memberships(self.restore), "V1 grants it to the migrating owner")
        role_to_role = {pair for pair in memberships(self.migrations) if pair[1].startswith("scheduler_")}
        self.assertLessEqual(role_to_role, memberships(self.restore))

    def test_the_dump_keeps_what_cannot_be_rebuilt(self):
        self.assertIn("pg_dump -U scheduler_owner -d scheduler -Fc --exclude-table-data=road_route_cache", self.backup)
        for table in ["tenant", "tenant_api_token", "api_request", "flyway_schema_history"]:
            self.assertIn(table, self.backup)
        self.assertIn("umask 077", self.backup, "dumps hold token digests and client addresses")
        self.assertIn('mv "$partial" "$dump"', self.backup, "a dump is kept only once it reads back")
        self.assertIn("BACKUP_KEEP_DAYS:-$retention", self.backup)

    def test_a_restore_is_asked_for_and_all_or_nothing(self):
        self.assertIn("--replace", self.restore)
        self.assertIn("--exit-on-error --single-transaction", self.restore)
        self.assertIn("run --rm -T scheduler-admin", self.restore, "migrate sets the serving login's password")
        self.assertIn("stop scheduler-booking scheduler-daily", self.restore)

    def test_the_runbook_points_at_both_scripts(self):
        runbook = read("docs/operations-runbook.md")
        for script in ["infra/backup-vm.sh", "infra/restore-vm.sh"]:
            self.assertIn(script, runbook)


if __name__ == "__main__":
    unittest.main()
