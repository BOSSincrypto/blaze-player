#!/usr/bin/env python3
"""Fail-closed coverage gate for the required contract test areas."""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def test_names(report_root: Path) -> set[str]:
    names: set[str] = set()
    for report in report_root.rglob("TEST-*.xml"):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as exc:
            raise ValueError(f"unparseable test report: {report}: {exc}") from exc
        for case in root.iter("testcase"):
            classname = case.attrib.get("classname", "")
            if classname:
                names.add(classname.rsplit(".", 1)[-1])
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="scripts/coverage_manifest.json")
    parser.add_argument("--reports", default="app/build/test-results")
    parser.add_argument("--output", default="build/reports/contract-coverage.json")
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    required = manifest.get("required", {})
    if not required:
        raise ValueError("coverage manifest has no required contract areas")
    executed = test_names(Path(args.reports))
    coverage = {
        area: sorted(set(classes) & executed)
        for area, classes in required.items()
    }
    missing = sorted(area for area, matches in coverage.items() if not matches)
    result = {
        "manifestVersion": manifest.get("version"),
        "executedTestClasses": sorted(executed),
        "coverage": coverage,
        "missing": missing,
        "status": "pass" if executed and not missing else "fail",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"coverage gate error: {exc}", file=sys.stderr)
        raise SystemExit(2)
