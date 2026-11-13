"""Guard the VM's releases: images tagged by commit, the build stamped into them, and infra/release-vm.sh's inputs."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def final_stage(dockerfile):
    return dockerfile[dockerfile.rindex("\nFROM "):]


class ReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compose = read("deploy/vm/docker-compose.yml")
        cls.script = read("infra/release-vm.sh")
        cls.example = dict(re.findall(r"^(\w+)=(\S*)$", read("deploy/vm/.env.example"), re.M))

    def test_images_are_tagged_by_commit(self):
        self.assertIn("image: ${SCHEDULER_IMAGE:-waterflex-scheduler-engine:${SCHEDULER_REVISION:-dev}}", self.compose)
        self.assertIn("image: waterflex-scheduler-routing:${ROUTING_REVISION:-dev}", self.compose)
        self.assertNotRegex(self.compose, r"waterflex-scheduler-\w+:vm\b")
        self.assertIn('args: { RELEASE: "${SCHEDULER_RELEASE:-dev}", REVISION: "${SCHEDULER_REVISION:-dev}" }', self.compose)
        self.assertIn('args: { RELEASE: "${SCHEDULER_RELEASE:-dev}", REVISION: "${ROUTING_REVISION:-dev}" }', self.compose)

    def test_every_image_carries_the_build_it_came_from(self):
        for service in ["scheduler-service", "routing-service"]:
            stage = final_stage(read(f"{service}/Dockerfile"))
            with self.subTest(service=service):
                self.assertIn("ARG RELEASE=dev", stage)
                self.assertIn("ARG REVISION=dev", stage)
                self.assertIn("LABEL org.opencontainers.image.version=$RELEASE org.opencontainers.image.revision=$REVISION", stage)
                # Over the base image's own (ubuntu), which docker image inspect would show otherwise.
                self.assertRegex(stage, r"org\.opencontainers\.image\.title=waterflex-scheduler-\w+ ")
        # /health and the start log read them (BuildInfo).
        self.assertIn("ENV SCHEDULER_RELEASE=$RELEASE SCHEDULER_REVISION=$REVISION", final_stage(read("scheduler-service/Dockerfile")))

    def test_the_vm_never_rebuilds_a_release_on_up(self):
        self.assertEqual(self.example["SCHEDULER_PULL_POLICY"], "missing")
        self.assertEqual(self.example["ROUTING_PULL_POLICY"], "missing")
        self.assertIn("pull_policy: ${ROUTING_PULL_POLICY:-build}", self.compose)
        for key in ["SCHEDULER_RELEASE", "SCHEDULER_REVISION", "ROUTING_REVISION"]:
            self.assertEqual(self.example[key], "", f"{key} is the release script's to write")
            self.assertIn(f"set_key {key} ", self.script)
        for key in ["SCHEDULER_PULL_POLICY", "ROUTING_PULL_POLICY"]:
            self.assertIn(f"set_key {key} missing", self.script, "a later up keeps the release's images")
        self.assertIn("export SCHEDULER_PULL_POLICY=missing ROUTING_PULL_POLICY=missing", self.script)

    def test_the_routing_tag_follows_everything_its_image_is_built_from(self):
        # A source the script missed would leave a changed routing build under an old tag, which the VM then reuses.
        dockerfile = read("routing-service/Dockerfile")
        build = dockerfile[:dockerfile.rindex("\nFROM ")]
        sources = [line.split()[1:-1] for line in build.splitlines() if line.startswith("COPY ") and "--from" not in line]
        paths = re.search(r"routing=\$\(git log -1 --format=%h --abbrev=12 -- (.*)\)", self.script)
        self.assertIsNotNone(paths)
        listed = paths.group(1).split()
        for source in [path for group in sources for path in group]:
            with self.subTest(source=source):
                self.assertTrue(any(source == path or source.startswith(path + "/") for path in listed), listed)

    def test_a_release_is_a_published_annotated_tag_on_main(self):
        self.assertIn(r"[[ $tag =~ ^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$ ]]", self.script)
        self.assertIn('[[ -z $(git status --porcelain -- . ":(exclude)$env_file") ]]', self.script)
        self.assertIn('git cat-file -t "refs/tags/$tag"', self.script)
        self.assertIn('git ls-remote "$remote" "refs/tags/$tag"', self.script)
        self.assertIn('git merge-base --is-ancestor "$tag^{commit}" "$main"', self.script)
        self.assertIn("deploy/vm/releases.log", read(".gitignore"))

    def test_scripts_run_in_linux_from_any_clone(self):
        attributes = read(".gitattributes")
        self.assertRegex(attributes, r"(?m)^/mvnw text eol=lf$")
        self.assertRegex(attributes, r"(?m)^\*\.sh text eol=lf$")


if __name__ == "__main__":
    unittest.main()
