"""Summarize complete matched booking reports without hiding unserved demand."""
import argparse
import hashlib
import json
from pathlib import Path


def summarize(paths):
    variants = []
    reference = None
    inputs = []
    for path in paths:
        raw = path.read_bytes()
        report = json.loads(raw)
        inputs.append({"report": path.name, "sha256": hashlib.sha256(raw).hexdigest()})
        for summary in report["summaries"]:
            source = summary["source"]
            rows = [row for row in report["cases"] if row["source"] == source]
            provenance = next(item["provenance"] for item in report["sources"] if item["source"] == source)
            assert len(rows) == summary["cases"] == 126, "Require the entire matrix"
            assert provenance["requests"] == 30 and summary["requests"] == 3780
            keys = {row["key"]: row["datasetFingerprint"] for row in rows}
            assert len(keys) == 126 and all(row["requests"] == 30 for row in rows)
            expected = {f"{size}/{workload}/{concurrency}/{cache}"
                        for size in (20, 30, 50)
                        for workload in ("SPARSE", "CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW", "ABSENCE", "NEAR_CAPACITY")
                        for concurrency in (1, 5, 10) for cache in ("cold", "warm")}
            assert keys.keys() == expected, "Unexpected matrix dimensions"
            identity = (keys, provenance["dates"], provenance["seed"], {row["routingIdentity"] for row in rows})
            if reference is None:
                reference = identity
            assert reference == identity, "Request fixtures, dates, seed or routing identity differ"
            served = sum(row["served"] for row in rows)
            assert served == summary["served"] and served > 0
            total_cost = sum(row["afterCost"] - row["beforeCost"] for row in rows)
            breakdown = []
            for concurrency in (1, 5, 10):
                selected = [row for row in rows if int(row["key"].split("/")[2]) == concurrency]
                breakdown.append({"concurrency": concurrency, "requests": len(selected) * 30,
                                  "served": sum(row["served"] for row in selected),
                                  "maximumCaseP95Ms": max(row["p95Ms"] for row in selected)})
            variants.append({**summary, "revision": provenance["revision"],
                             "artifactSha256": provenance["artifactSha256"],
                             "unserved": summary["requests"] - served,
                             "maximumCaseP95Ms": max(row["p95Ms"] for row in rows),
                             "casesAboveFiveSecondP95": sum(row["p95Ms"] > 5000 for row in rows),
                             "incrementalModeledCostCents": total_cost,
                             "incrementalModeledCostPerServedCents": total_cost / served,
                             "overtimeDeltaMinutes": sum(row["overtimeAfter"] - row["overtimeBefore"] for row in rows),
                             "waitingDeltaMinutes": sum(row["waitingAfter"] - row["waitingBefore"] for row in rows),
                             "roadDeltaSeconds": sum(row["roadSecondsAfter"] - row["roadSecondsBefore"] for row in rows),
                             "configuredBufferDeltaSeconds": sum(row["configuredBufferSecondsAfter"] - row["configuredBufferSecondsBefore"] for row in rows),
                             "meanDailyVarianceBefore": sum(row["meanDailyVarianceBefore"] for row in rows) / len(rows),
                             "meanDailyVarianceAfter": sum(row["meanDailyVarianceAfter"] for row in rows) / len(rows),
                             "maximumUtilization": max(row["maximumUtilization"] for row in rows),
                             "changedAssignments": sum(row["changedAssignments"] for row in rows),
                             "retimedAppointments": sum(row["retimedAppointments"] for row in rows),
                             "concurrency": breakdown})
    return {"inputs": inputs, "variants": variants, "limitations": [
        "Same input streams do not imply the same accepted demand. Read served counts alongside cost, overtime and fairness.",
        "Mean variance averages daily capacity-weighted variances; it is not a new fleet-wide fairness objective.",
        "Original audits use canonical timing. Early-departure ablations use their recorded timing model.",
        "Local processes shared hardware and routing. This is not a production capacity guarantee.",
        "Unknown legacy search completion remains unknown. Unserved is not interchangeable with incomplete.",
        "Per-case p95 uses 30 requests; maximum case p95 is distinct from pooled p95."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("reports", nargs="+", type=Path)
    args = parser.parse_args()
    result = summarize(args.reports)
    result["summarizerSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    with args.output.open("x", encoding="utf8") as target:
        json.dump(result, target, indent=2)
        target.write("\n")
    print(json.dumps({"variants": len(result["variants"]), "output": str(args.output)}))
