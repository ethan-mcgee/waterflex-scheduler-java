"""Guard the Maven Wrapper's mode: script only, so nothing in the repository depends on a wrapper jar or a JVM flag file."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROPERTIES = ".mvn/wrapper/maven-wrapper.properties"


def read_properties():
    pairs = re.findall(r"^([A-Za-z0-9]+)=(\S+)$", (ROOT / PROPERTIES).read_text(encoding="utf-8"), re.M)
    return dict(pairs)


class MavenWrapperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.properties = read_properties()

    def test_wrapper_runs_from_the_scripts_alone(self):
        self.assertEqual(self.properties.get("distributionType"), "only-script")
        self.assertFalse((ROOT / ".mvn/wrapper/maven-wrapper.jar").exists(), "only-script never reads the wrapper jar")
        for script in ["mvnw", "mvnw.cmd"]:
            with self.subTest(script=script):
                self.assertNotIn("maven-wrapper.jar", (ROOT / script).read_text(encoding="utf-8"))

    def test_distribution_is_a_pinned_release_zip(self):
        url = self.properties.get("distributionUrl")
        self.assertIsNotNone(url, f"{PROPERTIES} has no distributionUrl")
        self.assertRegex(url, r"^https://repo\.maven\.apache\.org/maven2/org/apache/maven/apache-maven/"
                              r"(\d+\.\d+\.\d+)/apache-maven-\1-bin\.zip$")

    def test_distribution_has_no_single_checksum(self):
        # The service images build in eclipse-temurin:25-jdk, which has no unzip, so mvnw fetches the .tar.gz there
        # while CI and mvnw.cmd fetch the .zip. One distributionSha256Sum cannot match both archives.
        self.assertNotIn("distributionSha256Sum", self.properties)

    def test_maven_needs_no_jvm_flags(self):
        # .mvn/jvm.config only enabled native access for JAnsi, which Maven 3.10.1 replaced with a pure-Java shim.
        self.assertFalse((ROOT / ".mvn/jvm.config").exists())
        self.assertNotIn("jvm.config", (ROOT / ".gitattributes").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
