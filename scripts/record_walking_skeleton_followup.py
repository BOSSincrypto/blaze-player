#!/usr/bin/env python3
"""Create CI evidence only after each deferred fixture ran exactly once."""

import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


OUT = Path(os.environ.get("FOLLOWUP_EVIDENCE", "build/reports/walking-skeleton-followup/evidence.json"))
HTTP = Path(os.environ.get("HTTP_MATRIX_REPORT", "build/reports/http-source-matrix.json"))

CASES = {
    "VAL-PLAYER-002": ("com.blaze.player.source.SourcePolicyTest", "ci deferred durable picker grant restart and revocation component"),
    "VAL-PLAYER-008": ("com.blaze.player.playback.AutoplayTransitionControllerTest", "ci deferred autoplay service transition component"),
}


def test_cases(classname, name):
    matches = []
    for report in Path("app/build/test-results").glob("**/TEST-*.xml"):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in root.iter("testcase"):
            if case.attrib.get("classname") == classname and case.attrib.get("name") == name:
                matches.append(str(report))
    return matches


def main():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    api_ok = bool(sdk) and any(
        p.is_dir() and p.name.startswith("android-") and p.name[8:].isdigit() and int(p.name[8:]) >= 31
        for p in (Path(sdk) / "platforms").glob("android-*")
    ) if sdk else False
    http = json.loads(HTTP.read_text()) if HTTP.exists() else {}
    executions = []
    for assertion, (classname, name) in CASES.items():
        reports = test_cases(classname, name)
        executions.append({"assertion": assertion, "test": f"{classname}.{name}", "count": len(reports), "reports": reports})
    http_checks = http.get("checks", [])
    http_ok = http.get("status") == "verified" and bool(http_checks) and all(c.get("passed") is True for c in http_checks)
    executions.append({"assertion": "VAL-PLAYER-006", "test": "scripts/http_source_matrix.py", "count": 1 if http_ok else 0,
                       "report": str(HTTP)})
    ci_ok = os.environ.get("GITHUB_ACTIONS", "").lower() == "true"
    valid = ci_ok and api_ok and http_ok and all(item["count"] == 1 for item in executions)
    report = {
        "status": "verified" if valid else "failed",
        "environment": {"ci": os.environ.get("GITHUB_ACTIONS") == "true", "jdk": "17", "android_api_minimum": 31},
        "commit": os.environ.get("GITHUB_SHA", ""),
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "executions": executions,
        "device_playback": "unverified",
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
