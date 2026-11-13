"""Guard the owner's rule that tokens are never written to files: the study scripts issue, pass and revoke them in memory."""
import re
import unittest
from pathlib import Path


INFRA = Path(__file__).resolve().parent
SCRIPTS = sorted(INFRA.glob("*.ps1"))


def read(path):
    return path.read_text(encoding="utf-8")


class StudyScriptTokenTest(unittest.TestCase):
    def test_no_script_reads_or_writes_a_token_file(self):
        for path in SCRIPTS:
            text = read(path)
            with self.subTest(script=path.name):
                self.assertNotIn("--token-file", text)
                self.assertNotRegex(text, r"\$TokenFile\b|ClientTokenDirectory")
                self.assertNotRegex(text, r"(?im)^.*(Set-Content|Out-File|Add-Content)[^\n]*token")

    def test_every_issued_token_is_revoked_in_a_finally_block(self):
        issuing = [path for path in SCRIPTS if "New-StudyToken" in read(path)]
        self.assertGreaterEqual(len(issuing), 4)
        for path in issuing:
            text = read(path)
            with self.subTest(script=path.name):
                self.assertIn("Import-Module (Join-Path $PSScriptRoot 'study-token.psm1') -Force", text)
                after = text.split("} finally {", 1)
                self.assertEqual(len(after), 2, "a finally block")
                self.assertIn("Remove-StudyToken", after[1])

    def test_a_supplied_token_comes_from_a_checked_variable(self):
        for path in SCRIPTS:
            text = read(path)
            if "$TokenEnv" not in text:
                continue
            with self.subTest(script=path.name):
                self.assertIn("[string]$TokenEnv = 'SCHEDULER_STUDY_TOKEN'", text)
                self.assertIn("Assert-StudyTokenVariable $TokenEnv", text)
                self.assertIn("'--token-env', $TokenEnv", text)

    def test_the_token_module_writes_no_file(self):
        module = read(INFRA / "study-token.psm1")
        self.assertNotRegex(module, r"Set-Content|Out-File|Add-Content|\bWrite-Output\b[^\n]*\$value")
        # A failed issue shows docker's output with the token withheld.
        self.assertIn("-replace '(?m)^token: \\S+', 'token: <withheld>'", module)


if __name__ == "__main__":
    unittest.main()
