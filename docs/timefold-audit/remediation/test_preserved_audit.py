"""The historical adapter must not exempt changed frozen evidence."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_preserved_audit as adapter


class PreservedAuditTests(unittest.TestCase):
    def exercise(self, changed=False):
        historical = adapter.historical
        original = historical.verify_historical
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); source = root / adapter.CHARACTERIZATION
            source.parent.mkdir(parents=True); source.write_bytes(b'current desired behavior')
            def verify(external):
                historical.verify_historical(b'current desired behavior',
                    {'path': adapter.CHARACTERIZATION, 'sha256': hashlib.sha256(b'original characterization').hexdigest()}, {})
                historical.verify_historical(b'changed' if changed else b'frozen evidence',
                    {'path': 'docs/timefold-audit/evidence/raw.log', 'sha256': hashlib.sha256(b'frozen evidence').hexdigest()}, {})
            with patch.object(historical, 'REPO', root), patch.object(historical, 'verify', side_effect=verify), \
                 patch.object(historical, 'git_bytes', return_value=b'original characterization') as git:
                try:
                    adapter.verify()
                finally:
                    self.assertIs(historical.verify_historical, original)
                git.assert_called_once_with(historical.REVIEWED, adapter.CHARACTERIZATION)

    def test_original_characterization_is_verified_without_reverting_current_regressions(self):
        self.exercise()

    def test_changed_frozen_evidence_still_fails_and_verifier_is_restored(self):
        with self.assertRaises(AssertionError): self.exercise(changed=True)

    def test_no_characterization_evidence_cannot_be_called_complete(self):
        historical = adapter.historical; original = historical.verify_historical
        with patch.object(historical, 'verify'):
            with self.assertRaises(RuntimeError): adapter.verify()
        self.assertIs(historical.verify_historical, original)


if __name__ == '__main__': unittest.main()
