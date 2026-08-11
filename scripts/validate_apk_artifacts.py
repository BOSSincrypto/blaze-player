#!/usr/bin/env python3
"""Validate exact APK outputs and emit provenance artifacts.

This intentionally fails before upload when either APK, its manifest, or its
checksum is not trustworthy. It uses apkanalyzer from the Android SDK rather
than treating a ZIP that merely has an .apk suffix as a valid application.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


PACKAGE = "com.blaze.player"
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def analyzer_value(analyzer: str, apk: Path, field: str) -> str:
    result = subprocess.run(
        [analyzer, "manifest", field, str(apk)],
        check=False, capture_output=True, text=True,
    )
    if result.returncode or not result.stdout.strip():
        raise ValueError(f"apkanalyzer could not read {field} from {apk}")
    return result.stdout.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate(apk: Path, variant: str, analyzer: str) -> dict:
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError(f"missing or zero-byte {variant} APK: {apk}")
    try:
        with zipfile.ZipFile(apk) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"corrupt ZIP/APK: {apk}")
            if variant == "release" and any(
                name.upper().startswith("META-INF/") and
                name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
                for name in archive.namelist()
            ):
                raise ValueError(f"release APK is signed: {apk}")
    except zipfile.BadZipFile as exc:
        raise ValueError(f"unparseable APK: {apk}") from exc

    package = analyzer_value(analyzer, apk, "application-id")
    if package != PACKAGE:
        raise ValueError(f"unexpected package for {variant}: {package}")
    version_code = analyzer_value(analyzer, apk, "version-code")
    version_name = analyzer_value(analyzer, apk, "version-name")
    minimum = analyzer_value(analyzer, apk, "min-sdk")
    target = analyzer_value(analyzer, apk, "target-sdk")
    if minimum != "31" or target != "37":
        raise ValueError(f"unexpected SDK identity: min={minimum}, target={target}")
    return {
        "variant": variant, "filename": apk.name, "sizeBytes": apk.stat().st_size,
        "sha256": sha256(apk), "package": package, "versionCode": version_code,
        "versionName": version_name, "minSdk": minimum, "targetSdk": target,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", required=True, type=Path)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--analyzer", default="apkanalyzer")
    args = parser.parse_args()
    debug = validate(args.debug, "debug", args.analyzer)
    release = validate(args.release, "release", args.analyzer)
    if debug["filename"] == release["filename"] or debug["sha256"] == release["sha256"]:
        raise ValueError("debug and release artifacts are not distinct")

    metadata = {
        "schemaVersion": 1,
        "commit": os.environ.get("GITHUB_SHA", "unknown"),
        "ref": os.environ.get("GITHUB_REF", "unknown"),
        "runId": os.environ.get("GITHUB_RUN_ID", "unknown"),
        "event": os.environ.get("GITHUB_EVENT_NAME", "unknown"),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "toolchain": {"gradle": os.environ.get("GRADLE_VERSION", "wrapper"),
                      "agp": os.environ.get("AGP_VERSION", "pinned"),
                      "kotlin": os.environ.get("KOTLIN_VERSION", "pinned"),
                      "jdk": os.environ.get("JAVA_VERSION", "17"),
                      "compileSdk": "35", "buildTools": "35.0.0"},
        "package": PACKAGE, "artifacts": [debug, release],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksum = args.output.with_name("SHA256SUMS")
    checksum.write_text("".join(f"{item['sha256']}  {item['filename']}\n" for item in (debug, release)), encoding="utf-8")
    print(json.dumps(metadata, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"artifact validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
