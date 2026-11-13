"""Guard the container hardening: digest-pinned images, non-root service images and the read-only road graph."""
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILES = ["docker-compose.yml", "docker-compose.map-preview.yml", "docker-compose.map-active.yml",
                 "deploy/vm/docker-compose.yml", "deploy/vm/sandbox.override.yml", "deploy/vm/rehearsal.override.yml"]
DOCKERFILES = ["scheduler-service/Dockerfile", "routing-service/Dockerfile",
               "web/Dockerfile", "infra/map-import/Dockerfile"]
JAVA_SERVICES = ["scheduler-service", "routing-service"]
# Images built from this repository are tagged locally, so they carry no registry digest.
LOCAL_IMAGE = re.compile(r"^(waterflex-[\w-]+:[\w.-]+|\$\{SCHEDULER_IMAGE:-waterflex-[\w-]+:[\w.-]+\})$")
PINNED = re.compile(r"^(?P<name>[\w./-]+:[\w.-]+)@sha256:[0-9a-f]{64}$")


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def service(text, name):
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [\w-]+:\n|^\S|\Z)", text, re.M | re.S)
    if match is None:
        raise AssertionError(f"no service {name}")
    return match.group(1)


class ImagePinTest(unittest.TestCase):
    def references(self):
        for relative in COMPOSE_FILES:
            for image in re.findall(r"^\s+image: (\S+)$", read(relative), re.M):
                yield relative, image
        for relative in DOCKERFILES:
            for image in re.findall(r"^FROM (\S+)", read(relative), re.M):
                yield relative, image

    def test_every_third_party_image_is_pinned_by_digest(self):
        for relative, image in self.references():
            with self.subTest(file=relative, image=image):
                if not LOCAL_IMAGE.match(image):
                    self.assertRegex(image, PINNED)

    def test_one_tag_has_one_digest_everywhere(self):
        digests = {}
        for relative, image in self.references():
            if PINNED.match(image):
                name = PINNED.match(image).group("name")
                with self.subTest(file=relative, image=name):
                    self.assertEqual(digests.setdefault(name, image), image)


class NonRootImageTest(unittest.TestCase):
    def test_java_services_run_as_uid_10001(self):
        for name in JAVA_SERVICES:
            with self.subTest(service=name):
                runtime = read(f"{name}/Dockerfile").split("\nFROM ")[-1]
                self.assertIn("useradd --system --uid 10001 --gid waterflex", runtime)
                # The user is set after the jar is copied and before the service starts.
                self.assertRegex(runtime, r"COPY --from=build [^\n]+\nUSER 10001:10001\n(EXPOSE \d+\n)?ENTRYPOINT ")


class VmComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = read("deploy/vm/docker-compose.yml")

    def test_services_built_here_drop_every_capability(self):
        self.assertIn('x-hardened: &hardened\n  cap_drop: [ALL]\n  security_opt: ["no-new-privileges:true"]\n', self.text)
        self.assertRegex(self.text, r"x-scheduler-image: &scheduler-image\n  <<: \*hardened\n")
        for name in ["routing-omaha", "prometheus"]:
            with self.subTest(service=name):
                self.assertTrue(service(self.text, name).startswith("    <<: *hardened\n"))

    def test_proxy_keeps_only_the_capabilities_nginx_needs(self):
        proxy = service(self.text, "scheduler-proxy")
        self.assertIn("    cap_drop: [ALL]\n    cap_add: [CHOWN, DAC_OVERRIDE, SETGID, SETUID]\n", proxy)
        self.assertIn('    security_opt: ["no-new-privileges:true"]\n', proxy)

    def test_routing_reads_its_map_volume_read_only(self):
        routing = service(self.text, "routing-omaha")
        self.assertIn('      ROUTING_GRAPH_READ_ONLY: "true"\n', routing)
        self.assertIn('    volumes: ["maps:/maps:ro"]\n', routing)


class DevelopmentComposeTest(unittest.TestCase):
    def test_serving_routing_mounts_maps_read_only(self):
        for relative, name in [("docker-compose.yml", "routing-service"), ("docker-compose.map-preview.yml", "routing-preview")]:
            with self.subTest(service=name):
                self.assertIn('    volumes: ["map-data:/maps:ro"]\n', service(read(relative), name))

    def test_only_routing_prepare_writes_the_graph(self):
        prepare = service(read("docker-compose.yml"), "routing-prepare")
        self.assertIn('    profiles: ["map-import"]\n', prepare)
        self.assertIn('      ROUTING_GRAPH_READ_ONLY: "false"\n', prepare)
        self.assertIn('    volumes: ["map-data:/maps"]\n', prepare)
        script = read("infra/prepare-map.ps1")
        # The flexible graph, then the contraction hierarchy graph.
        self.assertEqual(len(re.findall(r"--profile map-import run --rm [^\n]* routing-prepare\n", script)), 2)
        self.assertNotIn("routing-service --spring.main.web-application-type=none", script)


if __name__ == "__main__":
    unittest.main()
