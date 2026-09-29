"""Summarize JUnit reports without counting nested suites twice."""
import argparse
import glob
import os
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("patterns", nargs="+")
parser.add_argument("--label", required=True)
args = parser.parse_args()
files = sorted({file for pattern in args.patterns for file in glob.glob(pattern)})
cases = [case for file in files for case in ET.parse(file).getroot().iter("testcase")]
failures = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
skipped = sum(case.find("skipped") is not None for case in cases)
seconds = sum(float(case.get("time", "0")) for case in cases)
summary = f"### {args.label}\n\n{len(cases)} tests, {failures} failed, {skipped} skipped; summed test duration {seconds:.2f}s ({len(files)} reports).\n\n"
if not files:
    summary += "No reports were produced. Check preceding steps for setup or compilation failures.\n\n"
print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
        output.write(summary)
