#!/usr/bin/env python3
"""Fail-closed static acceptance checks for cross-area policy convergence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


CROSS_IDS = {f"VAL-CROSS-{index:03d}" for index in range(1, 21)}
REQUIRED_TERMS = (
    "content://",
    "http://",
    "https://",
    "HLS",
    "DASH",
    "DRM",
    "Chromecast",
    "Android Auto",
    "unverified",
)


def fail(message: str) -> None:
    raise ValueError(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root)
    contract = root / "scripts" / "coverage_manifest.json"
    workflow = root / ".github" / "workflows" / "ci.yml"
    readme = root / "README.md"
    source = root / "app" / "src" / "main" / "java" / "com" / "blaze" / "player" / "source" / "SourcePolicy.kt"

    for path in (contract, workflow, readme, source):
        if not path.is_file():
            fail(f"required cross-area file is missing: {path}")

    manifest = json.loads(contract.read_text(encoding="utf-8"))
    if not manifest.get("required", {}).get("cross-area") == ["CrossAreaContractTest"]:
        fail("coverage manifest must execute CrossAreaContractTest")

    workflow_text = workflow.read_text(encoding="utf-8")
    if "./gradlew --no-daemon test lint assembleDebug assembleRelease" not in workflow_text:
        fail("CI workflow is missing the required fail-closed four-task gate")
    if "verify_contract_coverage.py" not in workflow_text:
        fail("CI workflow is missing the contract coverage gate")
    if "verify_cross_area_contract.py" not in workflow_text:
        fail("CI workflow is missing the cross-area static gate")

    readme_text = readme.read_text(encoding="utf-8")
    missing_terms = [term for term in REQUIRED_TERMS if term not in readme_text]
    if missing_terms:
        fail(f"README is missing canonical policy terms: {', '.join(missing_terms)}")

    source_text = source.read_text(encoding="utf-8")
    forbidden = ("trustAll", "TrustAll", "HostnameVerifier { true", "Authorization", "Cookie")
    found_forbidden = [term for term in forbidden if term in source_text]
    if found_forbidden:
        fail(f"source policy contains forbidden credential/TLS behavior: {found_forbidden}")

    # Keep this check repository-local: every cross-area assertion is represented
    # in the checked-in acceptance inventory used by CI.
    ids_file = root / "scripts" / "cross_area_assertions.txt"
    if not ids_file.is_file():
        fail("cross-area assertion inventory is missing")
    ids = set(re.findall(r"VAL-CROSS-\d{3}", ids_file.read_text(encoding="utf-8")))
    if ids != CROSS_IDS:
        fail("cross-area assertion inventory must contain exactly VAL-CROSS-001..020")

    print(json.dumps({"status": "pass", "assertions": sorted(CROSS_IDS)}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"cross-area contract failed: {exc}")
        raise SystemExit(1)
