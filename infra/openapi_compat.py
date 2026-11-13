"""The public API compatibility gate: docs/api/openapi-v1.yaml changes only additively within v1.

It compares the spec on a branch with the spec it replaces (a pull request's base, or the previous commit on main) with
oasdiff (https://github.com/oasdiff/oasdiff) and fails when

- oasdiff finds a change that can break a v1 client, at warning level or above, that
  infra/openapi-compat/accepted-breaking-changes.txt does not list; or
- the API changed but info.version did not rise, or left major version 1.

A new enum value in a response (a new error code or reason) is additive by the v1 versioning rule, so
infra/openapi-compat/severity-levels.txt lowers that check to info. infra/openapi-compat/README.md has the rules.

Usage: python3 infra/openapi_compat.py --against <git-ref> [--oasdiff PATH]
       python3 infra/openapi_compat.py <base-spec> <head-spec> [--oasdiff PATH]
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
from typing import Callable, Sequence

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent
SPEC = "docs/api/openapi-v1.yaml"
CONFIG = pathlib.Path(__file__).resolve().parent / "openapi-compat"
LEVELS = CONFIG / "severity-levels.txt"
ACCEPTED = CONFIG / "accepted-breaking-changes.txt"
MAJOR = 1
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
INFO_VERSION = re.compile(r"""^  version:\s*(['"]?)([^'"\s#]+)\1\s*(?:#.*)?$""")
# oasdiff exits 1 when --fail-on finds a change, and from 100 up when it cannot run (an unreadable spec or setting).
FOUND = 1
# oasdiff's levels: 1 info, 2 warning, 3 error.
WARNING = 2

Version = tuple[int, int, int]
Runner = Callable[[Sequence[str]], subprocess.CompletedProcess]


def spec_version(text: str) -> Version:
    """info.version of an OpenAPI YAML document: the one `version:` key directly under the top-level `info:` key."""
    inside, found = False, []
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            inside = line.rstrip() == "info:"
            continue
        match = INFO_VERSION.match(line) if inside else None
        if match:
            found.append(match.group(2))
    if len(found) != 1:
        raise ValueError(f"Expected one info.version, found {len(found)}")
    parts = VERSION.match(found[0])
    if parts is None:
        raise ValueError(f"info.version {found[0]!r} is not MAJOR.MINOR.PATCH")
    major, minor, patch = (int(part) for part in parts.groups())
    return major, minor, patch


def text(version: Version) -> str:
    return ".".join(map(str, version))


def findings(output: str, command: str) -> list[dict]:
    """oasdiff's JSON list of changes, each checked to carry the fields the gate reads."""
    try:
        data = json.loads(output)
    except json.JSONDecodeError as error:
        raise ValueError(f"oasdiff {command} did not print JSON: {error}") from error
    if not isinstance(data, list):
        raise ValueError(f"oasdiff {command} printed {type(data).__name__}, not a list of changes")
    for change in data:
        if not isinstance(change, dict) or not all(isinstance(change.get(key), str) for key in ("id", "text")) \
                or isinstance(change.get("level"), bool) or not isinstance(change.get("level"), int):
            raise ValueError(f"oasdiff {command} printed a change without id, text and level: {change!r}")
    return data


def accept_line(change: dict) -> str:
    """The line accepted-breaking-changes.txt takes for a change: its method, path and text."""
    where = " ".join(part for part in (change.get("operation"), change.get("path")) if isinstance(part, str) and part)
    return f"{where} {change['text']}".strip()


def spec_at(ref: str) -> bytes:
    """The v1 spec as committed at a git ref, byte for byte."""
    result = subprocess.run(["git", "show", f"{ref}:{SPEC}"], cwd=REPOSITORY, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"git show {ref}:{SPEC} failed: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


def run_oasdiff(args: Sequence[str]) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), capture_output=True, text=True, encoding="utf-8", check=False)


def oasdiff(run: Runner, args: Sequence[str], command: str, allowed: tuple[int, ...]) -> list[dict]:
    result = run(args)
    if result.returncode not in allowed:
        raise RuntimeError(f"oasdiff {command} failed with exit code {result.returncode}: {result.stderr.strip()}")
    return findings(result.stdout, command)


def check(base: pathlib.Path, head: pathlib.Path, tool: str = "oasdiff", run: Runner = run_oasdiff) -> list[str]:
    """Every reason the head spec cannot replace the base spec within v1; empty when it can."""
    problems = []
    breaking = oasdiff(run, [tool, "breaking", str(base), str(head), "--fail-on", "WARN", "--severity-levels", str(LEVELS),
                             "--err-ignore", str(ACCEPTED), "--warn-ignore", str(ACCEPTED), "--format", "json"],
                       "breaking", (0, FOUND))
    blocking = [change for change in breaking if change["level"] >= WARNING]
    if blocking:
        listed = "\n".join(f"  [{change['id']}] {accept_line(change)}" for change in blocking)
        accept = "\n".join(accept_line(change) for change in blocking)
        problems.append(
            f"{len(blocking)} change(s) can break a v1 client:\n{listed}\n"
            "Make the change additive or publish it as /api/v2. The one exception v1 allows is narrowing a value to "
            "bounds no host sends, confirmed with WaterFlex Software first; for it, add these lines to "
            f"infra/openapi-compat/accepted-breaking-changes.txt.\nTo accept:\n{accept}")
    changed = oasdiff(run, [tool, "changelog", str(base), str(head), "--severity-levels", str(LEVELS), "--format", "json"],
                      "changelog", (0,))
    before = spec_version(base.read_text(encoding="utf-8"))
    after = spec_version(head.read_text(encoding="utf-8"))
    if after[0] != MAJOR:
        problems.append(f"info.version {text(after)} leaves major version {MAJOR}; a breaking change is published as "
                        f"/api/v{MAJOR + 1} in its own spec file, never in place.")
    elif after < before:
        problems.append(f"info.version went down from {text(before)} to {text(after)}.")
    elif changed and after == before:
        problems.append(f"The API changed ({len(changed)} change(s)) but info.version is still {text(before)}; raise "
                        "the minor version and add the change to the spec's version history and to the change table "
                        "in docs/stateless-api-design.md.")
    return problems


def report(base: pathlib.Path, head: pathlib.Path, tool: str) -> int:
    problems = check(base, head, tool)
    for problem in problems:
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print(f"::error title=Public API compatibility::{problem.splitlines()[0]}")
        print(problem, file=sys.stderr)
    if problems:
        return 1
    before = text(spec_version(base.read_text(encoding="utf-8")))
    after = text(spec_version(head.read_text(encoding="utf-8")))
    print(f"Compatible within v{MAJOR}: {before} to {after}.")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="The public API compatibility gate for docs/api/openapi-v1.yaml.")
    parser.add_argument("specs", nargs="*", type=pathlib.Path, metavar="SPEC",
                        help="the spec being replaced and the spec replacing it")
    parser.add_argument("--against", metavar="REF", help=f"compare {SPEC} as committed at this git ref with the working tree's")
    parser.add_argument("--oasdiff", default="oasdiff", help="the oasdiff executable")
    args = parser.parse_args(argv)
    if args.against is None:
        if len(args.specs) != 2:
            parser.error("give the base and head specs, or --against REF")
        return report(args.specs[0], args.specs[1], args.oasdiff)
    if args.specs:
        parser.error("--against compares with the working tree's spec; give no spec files with it")
    with tempfile.TemporaryDirectory() as directory:
        base = pathlib.Path(directory, "openapi-v1-base.yaml")
        base.write_bytes(spec_at(args.against))
        return report(base, REPOSITORY / SPEC, args.oasdiff)


if __name__ == "__main__":
    sys.exit(main())
