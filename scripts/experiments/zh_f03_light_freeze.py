#!/usr/bin/env python3
"""Freeze already validated REAL ZH-F03 runs into canonical 07/08/09 evidence."""

from __future__ import annotations

import argparse
import csv
import json
import os
from pathlib import Path
import sys
from typing import Any

from zh_f03_light_common import CASE_IDS, EvidenceError, load_attempts, read_json, sha256


def _atomic_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8", newline="")
    temporary.replace(path)


def _relative(path: Path, feature_root: Path) -> str:
    return Path(os.path.relpath(path.resolve(), feature_root.resolve())).as_posix()


def _file_entry(path: Path, feature_root: Path) -> dict[str, Any]:
    return {
        "path": _relative(path, feature_root),
        "sha256": sha256(path),
        "size": path.stat().st_size,
    }


def freeze(input_path: str, feature_root: Path, minimum_attempts: int) -> tuple[Path, Path, Path]:
    run_type_dir, attempts, counts = load_attempts(input_path, "REAL", minimum_attempts)
    summary_path = run_type_dir / "stat-summary.json"
    if not summary_path.is_file():
        raise EvidenceError("REAL stat-summary.json is missing; run the frozen statistic script first")
    summary = read_json(summary_path)
    if summary.get("runType") != "REAL" or summary.get("matrixPass") is not True:
        raise EvidenceError("REAL stat summary is not a passing matrix")
    if summary.get("countsByCase") != counts:
        raise EvidenceError("REAL stat summary counts do not match immutable attempts")

    attempts = sorted(attempts, key=lambda item: (item["manifest"]["caseId"], item["manifest"]["runId"]))
    manifest_lines = [json.dumps(item["manifest"], ensure_ascii=False, separators=(",", ":")) for item in attempts]

    inventory_runs = []
    for item in attempts:
        run_dir = Path(item["manifestPath"]).parent
        manifest = item["manifest"]
        evidence_fields = (
            "rawResultPath",
            "rawLogPath",
            "stateResetProof",
            "rawStateFile",
            "environmentSnapshotFile",
            "commandFile",
            "configSnapshotFile",
        )
        evidence = {}
        for field in evidence_fields:
            relative = manifest.get(field)
            if not isinstance(relative, str) or not relative:
                if field == "configSnapshotFile":
                    continue
                raise EvidenceError(f"{item['manifestPath']}: missing {field}")
            path = (run_dir / relative).resolve()
            try:
                path.relative_to(run_dir.resolve())
            except ValueError as exc:
                raise EvidenceError(f"{item['manifestPath']}: {field} escapes run directory") from exc
            if not path.is_file():
                raise EvidenceError(f"{item['manifestPath']}: missing evidence file {relative}")
            evidence[field] = _file_entry(path, feature_root)
        inventory_runs.append({
            "caseId": manifest["caseId"],
            "runId": manifest["runId"],
            "attempt": manifest["attempt"],
            "manifest": _file_entry(Path(item["manifestPath"]), feature_root),
            "evidence": evidence,
        })

    path_07 = feature_root / "07-run-manifest.jsonl"
    path_08 = feature_root / "08-原始结果清单.json"
    path_09 = feature_root / "09-统计汇总.csv"
    _atomic_text(path_07, "\n".join(manifest_lines) + "\n")
    inventory = {
        "schemaVersion": "ZH-F03-REAL-INVENTORY/1",
        "runType": "REAL",
        "minimumAttempts": minimum_attempts,
        "runCount": len(attempts),
        "statSummary": _file_entry(summary_path, feature_root),
        "runs": inventory_runs,
    }
    _atomic_text(path_08, json.dumps(inventory, ensure_ascii=False, indent=2) + "\n")

    rows = ["caseId,attemptCount,passCount,failCount,blockedCount,pass"]
    summary_by_case = {item["caseId"]: item for item in summary.get("cases", [])}
    for case_id in CASE_IDS:
        item = summary_by_case.get(case_id)
        if not isinstance(item, dict):
            raise EvidenceError(f"REAL stat summary is missing case: {case_id}")
        rows.append(
            f"{case_id},{item['attemptCount']},{item['passCount']},{item['failCount']},"
            f"{item['blockedCount']},{str(bool(item['pass'])).lower()}"
        )
    _atomic_text(path_09, "\n".join(rows) + "\n")
    return path_07, path_08, path_09


def main() -> int:
    parser = argparse.ArgumentParser(description="Freeze canonical ZH-F03 REAL evidence")
    parser.add_argument("--run-type", required=True, choices=("REAL", "FIXTURE_ONLY", "STUB"))
    parser.add_argument("--input", required=True)
    parser.add_argument("--feature-root", required=True)
    parser.add_argument("--minimum-attempts", type=int, default=3)
    args = parser.parse_args()
    if args.run_type != "REAL":
        print("FAIL_CLOSED: canonical ZH-F03 evidence accepts REAL runs only", file=sys.stderr)
        return 2
    try:
        outputs = freeze(args.input, Path(args.feature_root).resolve(), args.minimum_attempts)
    except (EvidenceError, OSError, KeyError, TypeError, ValueError, csv.Error) as exc:
        print(f"FAIL_CLOSED: {exc}", file=sys.stderr)
        return 2
    print("[freeze] " + " ".join(str(path) for path in outputs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
