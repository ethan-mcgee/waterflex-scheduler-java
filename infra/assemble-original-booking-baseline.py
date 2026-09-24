"""Assemble the explicitly split original baseline without discarding source evidence.

The primary run supplies every case except 50-tech NEAR_CAPACITY. The parallel
tail supplies precisely those six cases. This fixed partition is selected before
results are known. Raw sources, failures and excluded duplicate rows are retained.
"""
import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path
import subprocess


def load(path):
    raw = path.read_bytes()
    rows = [json.loads(line) for line in raw.decode("utf8").splitlines()]
    provenance = [row for row in rows if row.get("type") == "provenance"]
    if len(provenance) != 1:
        raise ValueError("Exactly one provenance record is required")
    return raw, rows, provenance[0]


def key(row):
    return row["size"], row["workload"], row["concurrency"], row["cache"]


def assemble(primary, tail, output):
    inputs = [load(primary), load(tail)]
    first, second = inputs[0][2], inputs[1][2]
    if first["revision"] != "2d17885141d4a901b06b3f9732530db85c951568":
        raise ValueError("This assembly is restricted to the unchanged original baseline")
    if not isinstance(first["artifactSha256"], str) or not re.fullmatch(r"[a-fA-F0-9]{64}", first["artifactSha256"]):
        raise ValueError("A recorded original artifact hash is required")
    if first["concurrencyValues"] != [1, 5, 10] or first["caches"] != ["cold", "warm"]:
        raise ValueError("The planned concurrency/cache matrix is required")
    for field in ("revision", "artifactSha256", "seed", "dates", "requests", "concurrencyValues", "caches", "serverMode"):
        if first[field] != second[field]:
            raise ValueError("Different experiment configuration: " + field)
    if first["serverMode"] != "legacy" or second["sizes"] != [50] or second["workloads"] != ["NEAR_CAPACITY"]:
        raise ValueError("Unexpected original-baseline partition")
    if first["sizes"] != [20, 30, 50] or set(first["workloads"]) != {
            "SPARSE", "CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW", "ABSENCE", "NEAR_CAPACITY"}:
        raise ValueError("The primary source must configure the full planned matrix")
    expected = {(size, work, concurrency, cache) for size in first["sizes"] for work in first["workloads"]
                for concurrency in first["concurrencyValues"] for cache in first["caches"]}
    selected = {}
    sources = []
    for index, (raw, rows, provenance) in enumerate(inputs):
        excluded = []
        failures = [row for row in rows if row.get("type") == "failure"]
        if index == 1 and failures:
            raise ValueError("The parallel tail must complete; failures cannot disappear during assembly")
        for row in rows:
            if row.get("type") != "case":
                continue
            case = key(row)
            if case not in expected:
                raise ValueError("Unexpected source case: " + str(case))
            belongs_to_tail = case[0] == 50 and case[1] == "NEAR_CAPACITY"
            if belongs_to_tail != (index == 1):
                if index == 1:
                    raise ValueError("The tail contains a case outside its declared partition")
                excluded.append(case)
                continue
            if case not in expected or case in selected:
                raise ValueError("Unexpected or duplicated selected case: " + str(case))
            if row.get("independentlyValidated") is not True or row.get("promiseViolations") != 0:
                raise ValueError("A selected case lacks successful independent validation")
            if len(row["attempts"]) != first["requests"]:
                raise ValueError("A selected case has an incomplete request stream")
            selected[case] = {**row, "sourceRunIndex": index}
        sources.append({"sourceRunIndex": index, "provenance": provenance,
                        "rawSha256": hashlib.sha256(raw).hexdigest(),
                        "archive": output.name + f".source-{index}.gz",
                        "excludedCaseKeys": excluded, "failureRecords": failures})
    if set(selected) != expected:
        raise ValueError("Selected matrix is incomplete; missing cases: " + str(sorted(expected - set(selected))))
    identities = {row["after"]["routingIdentity"] for row in selected.values()}
    if len(identities) != 1:
        raise ValueError("Routing identity changed across the selected matrix")
    provenance = {**first, "variant": "original-baseline-explicit-split", "harnessRevision": None,
                  "harnessSources": None, "auditRevision": None,
                  "assembly": {"revision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                               "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                               "partition": "Primary except 50/NEAR_CAPACITY; separate tail supplies that entire six-case group",
                               "sources": sources,
                               "limitations": ["Independent schemas and processes shared hardware and the fixture provider.",
                                                "Source provenance retains different harness and read-only audit revisions.",
                                                "Primary interruption or duplicate tail observations are retained explicitly.",
                                                "This assembly does not correct the earlier database connection incident; rechecks remain separate."]}}
    # Validate every input before producing output. Never replace earlier evidence.
    targets = [output] + [output.with_name(source["archive"]) for source in sources]
    if any(path.exists() for path in targets):
        raise FileExistsError("Assembly output already exists")
    for source, (raw, _, _) in zip(sources, inputs, strict=True):
        with output.with_name(source["archive"]).open("xb") as archive:
            archive.write(gzip.compress(raw))
    with output.open("x", encoding="utf8") as result:
        result.write(json.dumps(provenance) + "\n")
        for case in sorted(selected):
            result.write(json.dumps(selected[case]) + "\n")
    print(json.dumps({"cases": len(selected), "output": str(output), "sources": len(sources)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("primary", type=Path)
    parser.add_argument("tail", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    assemble(args.primary, args.tail, args.output)
