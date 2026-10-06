"""Read-only verification of the starting revision and preserved historical evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate receipt key: {key}")
        result[key] = value
    return result


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(receipt, maven_repository=None):
    require(receipt["schemaVersion"] == 1, "Unsupported baseline receipt version")
    revision = receipt["startingRevision"]
    reviewed = receipt["auditReviewedRevision"]
    for value in (revision, reviewed):
        require(re.fullmatch(r"[0-9a-f]{40}", value) is not None, "Full Git revision required")
    require(git("rev-parse", f"{revision}^{{commit}}").decode().strip() == revision,
            "Starting revision is unavailable")
    entries = receipt["files"]
    require(entries, "Baseline files required")
    paths = set()
    for item in entries:
        path = item["path"]
        require(path not in paths, f"Duplicate baseline path: {path}")
        paths.add(path)
        require(not Path(path).is_absolute() and ".." not in Path(path).parts,
                f"Repository-relative path required: {path}")
        content = git("show", f"{revision}:{path}")
        require(hashlib.sha256(content).hexdigest() == item["sha256"], f"Baseline hash: {path}")
        require(len(content) == item["bytes"], f"Baseline length: {path}")
        require(git("rev-parse", f"{revision}:{path}").decode().strip() == item["gitBlob"],
                f"Baseline Git identity: {path}")
        if item["preserve"]:
            require((ROOT / path).is_file(), f"Missing historical artifact: {path}")
            actual = git("hash-object", f"--path={path}", "--", str(ROOT / path)).decode().strip()
            require(actual == item["gitBlob"], f"Historical artifact changed: {path}")
    for item in receipt["sourceTrees"]:
        path = item["path"]
        tree = git("rev-parse", f"{revision}:{path}").decode().strip()
        require(tree == item["gitTree"], f"Source tree identity: {path}")
        delta = git("diff", "--name-only", reviewed, revision, "--", path).decode().splitlines()
        require(delta == item["changedPathsSinceAudit"], f"Reviewed-source delta: {path}")
    historical_paths = set(git("ls-tree", "-r", "--name-only", revision, "--",
                               "docs/timefold-audit", "docs/timefold-documentation").decode().splitlines())
    preserved = {item["path"] for item in entries if item["preserve"]}
    require(historical_paths == preserved, "Historical inventory must cover every baseline audit/source file")
    if maven_repository is not None:
        for item in receipt["dependencyArtifacts"]:
            path = item["repositoryPath"]
            require(not Path(path).is_absolute() and ".." not in Path(path).parts,
                    f"Repository-relative dependency required: {path}")
            artifact = maven_repository / path
            require(artifact.is_file(), f"Missing dependency: {path}")
            require(hashlib.sha256(artifact.read_bytes()).hexdigest() == item["sha256"],
                    f"Dependency hash: {path}")
    print(f"Verified {len(entries)} baseline files, {len(preserved)} preserved historical files, "
          f"and {len(receipt['sourceTrees'])} source trees at {revision}.")
    if maven_repository is None:
        print("Local dependency bytes not checked; supply --maven-repository to verify them.")
    else:
        print(f"Verified {len(receipt['dependencyArtifacts'])} recorded dependency artifacts.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, default=HERE / "baseline.json")
    parser.add_argument("--maven-repository", type=Path)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    verify(receipt, args.maven_repository)


if __name__ == "__main__":
    main()
