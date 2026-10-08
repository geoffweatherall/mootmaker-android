#!/usr/bin/env python3
"""Turn JUnit XML failures into check-run annotations and a job summary.

A cloud session can read check-run annotations through the REST API but cannot download logs or
artifacts, so every failure has to be said here, briefly: its first line, then each "Caused by" and
the first frame in this app's own code. A test helper's error (Compose's "Failed to inject touch
input", say) names the real reason only in its cause, and the frame says which test line it was.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

patterns = sys.argv[1:] or ["app/build/test-results/**/*.xml"]
failures, skipped, total = [], [], 0
for pattern in patterns:
    for path in glob.glob(pattern, recursive=True):
        for case in ET.parse(path).getroot().iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped.append(f"{case.get('classname')}.{case.get('name')}")
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                message = (bad.get("message") or (bad.text or "")).strip().splitlines()
                trace = (bad.text or "").splitlines()
                causes = [line.strip()[:200] for line in trace if line.strip().startswith("Caused by:")]
                frame = next((line.strip() for line in trace if line.strip().startswith("at com.mootmaker.")), None)
                detail = " | ".join(([message[0]] if message else []) + causes[:3] + ([frame] if frame else []))
                failures.append((f"{case.get('classname')}.{case.get('name')}", detail))

lines = [f"{total} tests, {len(failures)} failed, {len(skipped)} skipped"]
for name in skipped:
    print(f"::notice title=Test skipped::{name}")
for name, message in failures:
    print(f"::error title=Test failed::{name}: {message[:900]}")
    lines.append(f"- {name}: {message[:900]}")
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as f:
        f.write("\n".join(lines) + "\n")
print("\n".join(lines))
