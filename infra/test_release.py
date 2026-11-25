"""Guard the VM's releases: images tagged by commit, the build stamped into them, and infra/release-vm.sh's inputs."""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REVISION = re.compile(r"^[0-9a-f]{12}$")


def bash():
    """Git Bash on Windows (plain bash there is WSL, which reads C:/ paths as relative folders), bash elsewhere."""
    if sys.platform == "win32":
        path = Path(r"C:\Program Files\Git\bin\bash.exe")
        return str(path) if path.exists() else None
    return shutil.which("bash")


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def final_stage(dockerfile):
    return dockerfile[dockerfile.rindex("\nFROM "):]


class ReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compose = read("deploy/vm/docker-compose.yml")
        cls.script = read("infra/release-vm.sh")
        cls.revisions = read("infra/image-revisions.sh")
        cls.workflow = read(".github/workflows/images.yml")
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
        paths = re.search(r'routing=\$\(git log -1 --format=%h --abbrev=12 "\$commit" -- (.*)\)', self.revisions)
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
        self.assertIn('git merge-base --is-ancestor "$tag^{commit}" "$main_commit"', self.script)
        self.assertIn("deploy/vm/releases.log", read(".gitignore"))

    def test_every_image_name_comes_from_one_script(self):
        # Four places name the images; one that computed them differently would look for an image CI never pushed.
        for caller in ["infra/release-vm.sh", "infra/ship-images.sh", "infra/deploy-vm.ps1", ".github/workflows/images.yml"]:
            with self.subTest(caller=caller):
                text = read(caller)
                self.assertIn("infra/image-revisions.sh", text)
                self.assertNotIn("--abbrev=12", text, "only infra/image-revisions.sh lists routing's inputs")

    def test_the_release_script_is_read_whole_before_it_checks_out_the_tag(self):
        # The checkout replaces the running file; bash reads a script as it goes unless the body is one function.
        self.assertIn("\nmain() {\n", self.script)
        self.assertTrue(self.script.rstrip().endswith('main "$@"'))
        self.assertLess(self.script.index("bash infra/image-revisions.sh"), self.script.index("checkout --quiet --detach"),
                        "the revisions come from the running checkout, so a rollback to an older tag gets them too")
        for line in self.script.splitlines():
            if line and not line.startswith(("#", " ", "}", "set -euo pipefail", "main() {", 'main "$@"')):
                self.fail(f"a command outside main: {line}")

    def test_the_vm_pulls_images_and_never_builds_them_by_default(self):
        self.assertIn('docker pull --quiet "$registry/$image"', self.script)
        self.assertIn('docker tag "$registry/$image" "$image"', self.script)
        self.assertIn("${RELEASE_BUILD_ON_VM:-0} == 1", self.script)
        # Whatever brought the image, its label must name the commit before anything runs it.
        self.assertLess(self.script.index("docker pull --quiet"), self.script.index('[[ $built == "$commit" ]]'))
        self.assertIn('"${sources[*]}" >> "$log"', self.script, "the release log records where each image came from")

    def test_the_images_workflow_never_runs_pull_request_code_with_a_write_token(self):
        on = self.workflow[self.workflow.index("\non:"):self.workflow.index("\npermissions:")]
        self.assertNotIn("pull_request", on)
        self.assertIn("workflows: [CI]", on)
        self.assertIn("branches: [main]", on)
        self.assertIn("workflow_dispatch:", on)
        self.assertIn("\npermissions:\n  contents: read\n", self.workflow)
        self.assertEqual(self.workflow.count("packages: write"), 1)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", self.workflow)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", self.workflow)
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertIn("fetch-depth: 0", self.workflow, "the routing tag is a commit from history")

    def test_a_replica_serves_the_release_from_the_env_file(self):
        # An image is built per commit before any tag exists, so the release name cannot come from the image.
        runtime = self.compose[self.compose.index("x-scheduler-runtime:"):self.compose.index("x-scheduler-replica:")]
        self.assertIn("SCHEDULER_RELEASE: ${SCHEDULER_RELEASE:-dev}", runtime)
        self.assertIn("--build-arg RELEASE=unreleased", self.workflow)

    def test_image_revisions_names_a_commit_or_nothing(self):
        shell = bash()
        if shell is None:
            self.skipTest("no bash")
        shallow = subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=ROOT, capture_output=True, text=True)
        if shallow.stdout.strip() != "false":
            self.skipTest("a shallow clone has no history for the routing tag")
        head = subprocess.run([shell, "infra/image-revisions.sh", "HEAD"], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(head.returncode, 0, head.stderr)
        engine, routing = head.stdout.split()
        self.assertRegex(engine, REVISION)
        self.assertRegex(routing, REVISION)
        missing = subprocess.run([shell, "infra/image-revisions.sh", "no-such-ref"], cwd=ROOT, capture_output=True, text=True)
        self.assertNotEqual(missing.returncode, 0)
        self.assertEqual(missing.stdout, "", "a failure prints no image names")

    def test_a_test_stage_refuses_a_malformed_commit(self):
        shell = bash()
        if shell is None:
            self.skipTest("no bash")
        with tempfile.TemporaryDirectory() as home:
            (Path(home) / "live.env").write_text("# a stand-in for deploy/vm/.env\n", encoding="utf-8")
            env = dict(os.environ, PERF_HOME=home, CHECKOUT=str(ROOT), LIVE_ENV=str(Path(home) / "live.env"),
                       LIVE_COMPOSE_FILES=str(ROOT / "deploy" / "vm" / "docker-compose.yml").replace("\\", "/"))
            for setting in ["SCHEDULER_REVISION=main", "ROUTING_REVISION=ABCDEF123456", "SCHEDULER_REVISION=8acf5d1b51c", "SCHEDULER_IMAGE=x"]:
                with self.subTest(setting=setting):
                    run = subprocess.run([shell, "infra/vm-perf/vm-stage.sh", "probe", setting], cwd=ROOT, env=env,
                                         capture_output=True, text=True)
                    self.assertNotEqual(run.returncode, 0)
                    self.assertRegex(run.stderr + run.stdout, r"12-character commit|may not set")
                    self.assertEqual(list(Path(home).glob("stages/*")), [], "nothing was written for a refused stage")
            # A well-formed commit passes and goes on to fetch its image, which a registry that cannot exist refuses.
            env["SCHEDULER_IMAGE_REGISTRY"] = "registry.invalid/none"
            run = subprocess.run([shell, "infra/vm-perf/vm-stage.sh", "probe", "SCHEDULER_REVISION=8acf5d1b51cc"], cwd=ROOT, env=env,
                                 capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertNotIn("12-character", run.stderr + run.stdout)
            self.assertRegex(run.stderr + run.stdout, r"waterflex-scheduler-engine:8acf5d1b51cc|GB free")
            self.assertEqual(list(Path(home).glob("stages/*")), [], "nothing was written for a stage whose image is missing")

    def test_scripts_run_in_linux_from_any_clone(self):
        attributes = read(".gitattributes")
        self.assertRegex(attributes, r"(?m)^/mvnw text eol=lf$")
        self.assertRegex(attributes, r"(?m)^\*\.sh text eol=lf$")


if __name__ == "__main__":
    unittest.main()
