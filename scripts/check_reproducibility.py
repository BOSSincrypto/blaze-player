#!/usr/bin/env python3
"""Compare functional provenance from two pinned builds.

APK bytes may differ because packaging timestamps or signing metadata change.
Package, variant, version, manifest identity, and toolchain must not differ.
"""

from __future__ import annotations

import json
import sys
import os
from pathlib import Path


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"metadata is not an object: {path}")
    return value


def functional(value: dict) -> dict:
    artifacts = []
    for item in value.get("artifacts", []):
        artifacts.append({
            key: item.get(key)
            for key in ("variant", "filename", "package", "versionCode", "versionName", "minSdk", "targetSdk")
        })
    return {
        "package": value.get("package"),
        "toolchain": value.get("toolchain"),
        "artifacts": artifacts,
    }


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: check_reproducibility.py FIRST SECOND", file=sys.stderr)
        return 2
    first = functional(load(Path(sys.argv[1])))
    second = functional(load(Path(sys.argv[2])))
    report_path = Path("build/reproducibility/report.json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    result = {"schemaVersion": 1, "status": "pass", "compared": first,
              "commit": os.environ.get("GITHUB_SHA", "unknown")}
    if first != second:
        result = {"schemaVersion": 1, "status": "fail", "first": first,
                  "second": second, "commit": os.environ.get("GITHUB_SHA", "unknown")}
        report_path.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(result, sort_keys=True), file=sys.stderr)
        return 1
    report_path.write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"reproducibility check failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
