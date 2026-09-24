"""Assemble the explicitly split original baseline without discarding source evidence.

The primary run supplies every case except 50-tech NEAR_CAPACITY. The parallel
tail supplies precisely those six cases. An optional separate ABSENCE tail also
replaces its entire six-case group. Partitions are selected before results are
known. Raw sources, failures and excluded duplicate rows are retained.
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


def correct_rechecks(original, dispersed, mixed, output):
    """Replace only the four declared connection-incident rechecks, retaining all sources."""
    inputs = [load(original), load(dispersed), load(mixed)]
    first = inputs[0][2]
    if first["revision"] != "2d17885141d4a901b06b3f9732530db85c951568" or "assembly" not in first:
        raise ValueError("Correction requires the complete explicitly assembled original baseline")
    rows = [row for row in inputs[0][1] if row.get("type") == "case"]
    cases = {key(row): row for row in rows}
    if len(rows) != 126 or len(cases) != 126:
        raise ValueError("The original matrix must contain all 126 unique cases")
    replaced = []
    for index, workload, concurrency in [(1, "DISPERSED", 10), (2, "MIXED_SKILL", 1)]:
        _, source_rows, provenance = inputs[index]
        for field in ("revision", "artifactSha256", "seed", "dates", "requests", "serverMode"):
            if first[field] != provenance[field]:
                raise ValueError("Recheck configuration differs: " + field)
        if provenance["sizes"] != [20] or provenance["workloads"] != [workload] or provenance["concurrencyValues"] != [concurrency] or provenance["caches"] != ["cold", "warm"]:
            raise ValueError("Unexpected recheck partition")
        if any(row.get("type") == "failure" for row in source_rows):
            raise ValueError("A failed recheck cannot replace the original observations")
        rechecks = [row for row in source_rows if row.get("type") == "case"]
        expected = {(20, workload, concurrency, cache) for cache in ("cold", "warm")}
        if len(rechecks) != 2 or {key(row) for row in rechecks} != expected:
            raise ValueError("Recheck cases are missing or duplicated")
        for row in rechecks:
            case = key(row)
            prior = cases[case]
            if not isinstance(prior.get("datasetFingerprint"), str) or prior["datasetFingerprint"] != row.get("datasetFingerprint"):
                raise ValueError("Recheck request stream differs")
            if row.get("independentlyValidated") is not True or row.get("promiseViolations") != 0 or len(row["attempts"]) != first["requests"]:
                raise ValueError("Recheck lacks complete independently validated observations")
            if prior["after"]["routingIdentity"] != row["after"]["routingIdentity"]:
                raise ValueError("Recheck routing identity differs")
            cases[case] = {**row, "correctionSourceIndex": index}
            replaced.append(case)
    sources = [{"source": index, "archive": output.name + f".source-{index}.gz",
                "rawSha256": hashlib.sha256(raw).hexdigest(), "provenance": provenance}
               for index, (raw, _, provenance) in enumerate(inputs)]
    provenance = {**first, "variant": "original-baseline-explicit-connection-rechecks", "correction": {
        "reason": "Declared whole cold/warm pairs for the shared database connection incident; original failures remain archived.",
        "replacedCaseKeys": sorted(replaced), "sources": sources,
        "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}}
    targets = [output] + [output.with_name(source["archive"]) for source in sources]
    if any(path.exists() for path in targets):
        raise FileExistsError("Correction output already exists")
    for source, (raw, _, _) in zip(sources, inputs, strict=True):
        with output.with_name(source["archive"]).open("xb") as archive:
            archive.write(gzip.compress(raw))
    with output.open("x", encoding="utf8") as stream:
        stream.write(json.dumps(provenance) + "\n")
        for case in sorted(cases):
            stream.write(json.dumps(cases[case]) + "\n")
    print(json.dumps({"cases": len(cases), "explicitlyReplaced": len(replaced), "output": str(output)}))


def assemble(primary, tail, output, absence_tail=None, middle_tail=None, near_parallel=None):
    inputs = [load(primary), load(tail)]
    if absence_tail is not None:
        inputs.append(load(absence_tail))
    if middle_tail is not None:
        if absence_tail is None:
            raise ValueError("The middle continuation requires the declared absence partition")
        inputs.append(load(middle_tail))
    if near_parallel is not None:
        if middle_tail is None:
            raise ValueError("The near-capacity continuation requires the declared middle and absence partitions")
        inputs.append(load(near_parallel))
    first, second = inputs[0][2], inputs[1][2]
    if first["revision"] != "2d17885141d4a901b06b3f9732530db85c951568":
        raise ValueError("This assembly is restricted to the unchanged original baseline")
    if not isinstance(first["artifactSha256"], str) or not re.fullmatch(r"[a-fA-F0-9]{64}", first["artifactSha256"]):
        raise ValueError("A recorded original artifact hash is required")
    if first["concurrencyValues"] != [1, 5, 10] or first["caches"] != ["cold", "warm"]:
        raise ValueError("The planned concurrency/cache matrix is required")
    for index, (_, _, other) in enumerate(inputs[1:], start=1):
        fields = ("revision", "artifactSha256", "seed", "dates", "requests", "caches", "serverMode")
        if index != 4:
            fields += ("concurrencyValues",)
        for field in fields:
            if first[field] != other[field]:
                raise ValueError("Different experiment configuration: " + field)
    if first["serverMode"] != "legacy" or second["sizes"] != [50] or second["workloads"] != ["NEAR_CAPACITY"]:
        raise ValueError("Unexpected original-baseline partition")
    if absence_tail is not None and (inputs[2][2]["sizes"] != [50] or inputs[2][2]["workloads"] != ["ABSENCE"]):
        raise ValueError("Unexpected absence partition")
    middle_groups = {"DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW"}
    if middle_tail is not None and (inputs[3][2]["sizes"] != [50] or set(inputs[3][2]["workloads"]) != middle_groups):
        raise ValueError("Unexpected middle continuation partition")
    if near_parallel is not None and (inputs[4][2]["sizes"] != [50] or inputs[4][2]["workloads"] != ["NEAR_CAPACITY"] or inputs[4][2]["concurrencyValues"] != [5, 10]):
        raise ValueError("Unexpected near-capacity concurrency partition")
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
        if index > 0 and failures and not (index == 1 and near_parallel is not None):
            raise ValueError("The parallel tail must complete; failures cannot disappear during assembly")
        for row in rows:
            if row.get("type") != "case":
                continue
            case = key(row)
            if case not in expected:
                raise ValueError("Unexpected source case: " + str(case))
            partition = (4 if near_parallel is not None and case[2] > 1 else 1) if case[0] == 50 and case[1] == "NEAR_CAPACITY" else (
                2 if absence_tail is not None and case[0] == 50 and case[1] == "ABSENCE" else (
                    3 if middle_tail is not None and case[0] == 50 and case[1] in middle_groups else 0))
            if partition != index:
                if index > 0 and not (index == 1 and near_parallel is not None and partition == 4):
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
                               "partition": "Primary except 50/NEAR_CAPACITY; separate tail supplies that entire six-case group"
                                            + ("; third source supplies the entire 50/ABSENCE six-case group" if absence_tail is not None else "")
                                            + ("; fourth source supplies all 18 cases in 50/DISPERSED, 50/MIXED_SKILL and 50/TIGHT_WINDOW after the primary transport timeout" if middle_tail is not None else "")
                                            + ("; fifth source replaces the four 50/NEAR_CAPACITY concurrency 5/10 cases after the near-capacity transport timeout, retaining sequential cases in source two" if near_parallel is not None else ""),
                               "sources": sources,
                               "limitations": ["Independent schemas and processes shared hardware and the fixture provider.",
                                                "Source provenance retains different harness and read-only audit revisions.",
                                                "Primary interruption or duplicate tail observations are retained explicitly.",
                                                "Each source retains its measurement timeout; longer observation does not change the original server.",
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
    parser.add_argument("--absence-tail", type=Path)
    parser.add_argument("--middle-tail", type=Path)
    parser.add_argument("--near-parallel", type=Path)
    parser.add_argument("--correct-rechecks", action="store_true", help="Interpret primary, tail, output as assembled baseline, dispersed recheck, corrected output; --mixed-recheck is required")
    parser.add_argument("--mixed-recheck", type=Path)
    args = parser.parse_args()
    if args.correct_rechecks:
        if args.mixed_recheck is None or args.absence_tail is not None or args.middle_tail is not None or args.near_parallel is not None:
            parser.error("Correction requires --mixed-recheck and no assembly tail options")
        correct_rechecks(args.primary, args.tail, args.mixed_recheck, args.output)
    else:
        if args.mixed_recheck is not None:
            parser.error("--mixed-recheck requires --correct-rechecks")
        assemble(args.primary, args.tail, args.output, args.absence_tail, args.middle_tail, args.near_parallel)
